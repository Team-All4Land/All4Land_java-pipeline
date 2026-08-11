package com.onnara.extract.detect;

import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DirectoryEntry;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DocumentEntry;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DocumentInputStream;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 문서가 <b>잠겨 있는지</b>를 컨테이너를 직접 열어 판정한다 — 예외 메시지가 아니라 파일이 근거다.
 *
 * <p>잠긴 문서는 라이브러리 입장에서 그냥 깨진 파일로 보인다. hwplib은
 * {@code "This is not paragraph."}, hwpxlib은 {@code "Invalid byte 1 of 1-byte UTF-8
 * sequence"}를 던진다 — 둘 다 암호 얘기를 한마디도 하지 않는다. 그 메시지만 보고 분류하면
 * <b>재저장 한 번이면 살아날 문서가 "손상"으로 묻힌다.</b> 그래서 헤더를 직접 본다.
 *
 * <p>가려내야 할 잠금이 두 갈래이고, 둘은 대응이 전혀 다르다.
 *
 * <ul>
 *   <li><b>배포용 문서</b>({@link Lock#DISTRIBUTION}) — 열쇠가 파일 안에 있어 <b>우리도 읽을 수
 *       있다</b>(hwplib이 ViewText를 복호화한다). 그래서 {@link #requireUnlocked}는 이 갈래를
 *       막지 않는다. 판정 결과는 실패했을 때 사유를 가르는 데만 쓴다.</li>
 *   <li><b>사용자 암호</b>({@link Lock#PASSWORD}) — 암호를 아는 사람에게 해제본을 받아야 한다.
 *       우리 쪽에서 할 수 있는 일이 없다.</li>
 * </ul>
 *
 * <p>판정에 실패하면 {@link Lock#NONE}이다. 잠기지 않은 문서를 잠겼다고 하는 쪽이 훨씬
 * 나쁘다 — 진짜 손상을 배포용으로 오진하면 고칠 수 없는 파일을 계속 재저장해 보게 된다.
 */
public final class EncryptionProbe {

    /** 문서에 걸린 잠금의 갈래. */
    public enum Lock {

        /** 잠겨 있지 않다(또는 판정하지 못했다). */
        NONE,

        /** 배포용 문서 — 한글에서 일반 문서로 다시 저장하면 읽을 수 있다. */
        DISTRIBUTION,

        /** 사용자 암호 — 해제본이 필요하다. */
        PASSWORD
    }

    /** HWP5 FileHeader에서 속성 비트필드가 시작하는 오프셋. */
    private static final int PROPERTIES_OFFSET = 36;

    /** HWP5 속성 비트 1 — 사용자 암호가 걸려 있다. */
    private static final int FLAG_PASSWORD = 0x02;

    /** HWP5 속성 비트 2 — 배포용 문서다(본문이 ViewText에 암호화돼 있다). */
    private static final int FLAG_DISTRIBUTION = 0x04;

    /** HWPX 매니페스트에서 암호화된 항목을 가리키는 요소 이름. */
    private static final String ENCRYPTION_DATA = "encryption-data";

    /** HWPX 패키지 정의에서 배포용임을 가리키는 속성. */
    private static final String DISTRIBUTION_ATTRIBUTE = "distribution=\"1\"";

    /** 인스턴스화 방지 — 정적 판정 함수만 제공하는 유틸리티 클래스. */
    private EncryptionProbe() {
    }

    /**
     * 파일이 잠겨 있는지 판정한다.
     *
     * <p>어떤 이유로든 판정하지 못하면 {@link Lock#NONE}을 돌려준다 — 예외를 던지지 않는다.
     * 이 프로브는 <b>이미 실패한 파일을 분류하거나 실패를 앞당기는</b> 자리에서 불리므로,
     * 여기서 새 예외를 만들면 진짜 원인을 가린다.
     */
    public static Lock probe(Path file) {
        try {
            return switch (DocFormat.of(file)) {
                case HWP5 -> probeHwp5(file);
                case HWPX -> probeHwpx(file);
                default -> Lock.NONE;
            };
        } catch (IOException | RuntimeException e) {
            return Lock.NONE;
        }
    }

    /**
     * <b>우리가 열 수 없는</b> 문서면 곧바로 사람이 읽을 수 있는 예외를 던진다 —
     * 판별기·추출기의 진입점에서 쓴다.
     *
     * <p>분류는 {@link FailureClassifier}가 하지만, 로그와 원인 체인에 남는 <b>첫 문장</b>도
     * 읽을 수 있어야 한다. 잠긴 파일을 라이브러리에 그대로 넘기면 원인 체인 맨 끝에
     * {@code "Invalid byte 1 of 1-byte UTF-8 sequence"} 같은 무의미한 문장이 남는다.
     *
     * <p>막는 기준은 "잠겼는가"가 아니라 <b>"우리 손으로 열 수 있는가"</b>다. 그래서 근거도 판정
     * 문구도 {@link DocProtection} 하나에서만 가져온다 — 여기와 {@link FailureClassifier}가 서로
     * 다른 근거를 보면 "판별은 막았는데 실패 갈래는 딴소리를 한다"가 된다.
     *
     * <ul>
     *   <li><b>암호·DRM</b> — 키가 파일 밖에 있다. 넘겨 봐야 암호문을 파싱하다 죽으므로 막는다.</li>
     *   <li><b>HWP 5.0 배포용</b> — 열쇠가 파일 안에 있어 hwplib이 ViewText를 복호화한다.
     *       <b>통과시킨다</b> — 여기서 걸러 내면 정상 추출되던 문서가 통째로 실패로 돌아선다
     *       (→ {@code ProtectedDocumentTest}).</li>
     *   <li><b>HWPX 배포용</b> — hwpxlib에는 복호화 코드가 아예 없어 우리는 못 읽는다. 매니페스트가
     *       암호화를 선언하므로 암호 갈래로 잡혀 막힌다.</li>
     * </ul>
     */
    public static void requireUnlocked(Path file) throws IOException {
        DocProtection protection = DocProtection.of(file, DocFormat.of(file));
        if (protection.kind() == DocProtection.Kind.PASSWORD
                || protection.kind() == DocProtection.Kind.DRM) {
            throw new IOException(protection.detail() + ": " + file);
        }
    }

    /** HWP 5.0 — FileHeader 속성 비트만 읽는다(문서 전체를 파싱하지 않는다). */
    private static Lock probeHwp5(Path file) throws IOException {
        byte[] fileHeader;
        try (InputStream in = Files.newInputStream(file)) {
            DirectoryEntry root = new POIFSFileSystem(in).getRoot();
            if (!root.hasEntry("FileHeader")) {
                return Lock.NONE;
            }
            DocumentEntry entry = (DocumentEntry) root.getEntry("FileHeader");
            try (DocumentInputStream stream = new DocumentInputStream(entry)) {
                fileHeader = stream.readAllBytes();
            }
        }
        if (fileHeader.length < PROPERTIES_OFFSET + 4) {
            return Lock.NONE;
        }
        int properties = ByteBuffer.wrap(fileHeader, PROPERTIES_OFFSET, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        if ((properties & FLAG_DISTRIBUTION) != 0) {
            return Lock.DISTRIBUTION;
        }
        return (properties & FLAG_PASSWORD) != 0 ? Lock.PASSWORD : Lock.NONE;
    }

    /**
     * HWPX — 매니페스트가 암호화를 선언했는지 본다.
     *
     * <p>{@code META-INF/manifest.xml}은 <b>암호화되지 않은 채로</b> 들어 있고, 암호화된
     * 항목마다 {@code <odf:encryption-data>}로 알고리즘·salt를 적어 둔다. 그것이 있으면
     * 본문 XML이 암호문이라는 뜻이다. 배포용인지 사용자 암호인지는 역시 평문으로 남는
     * {@code Contents/content.hpf}의 {@code hpf:distribution} 속성이 가른다.
     */
    private static Lock probeHwpx(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (!contains(zip, "META-INF/manifest.xml", ENCRYPTION_DATA)) {
                return Lock.NONE;
            }
            return contains(zip, "Contents/content.hpf", DISTRIBUTION_ATTRIBUTE)
                    ? Lock.DISTRIBUTION
                    : Lock.PASSWORD;
        }
    }

    /** ZIP 안의 항목 하나를 읽어 특정 문자열을 담고 있는지 본다(없는 항목이면 false). */
    private static boolean contains(ZipFile zip, String entryName, String needle) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return false;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(needle);
        }
    }
}
