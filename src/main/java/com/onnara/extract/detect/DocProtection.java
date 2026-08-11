package com.onnara.extract.detect;

import kr.dogfoot.hwplib.object.fileheader.FileHeader;
import kr.dogfoot.hwplib.reader.ForFileHeader;
import kr.dogfoot.hwplib.util.compoundFile.reader.CompoundFileReader;
import kr.dogfoot.hwplib.util.compoundFile.reader.StreamReader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 문서가 <b>어떤 보호를 받고 있는지</b>만 알아내는 판별기 — 열지 못한 파일의 사유를 가르는 근거다.
 *
 * <p>"열리지 않는다"는 사실은 같아도 대응은 전혀 다르다. <b>DRM 보안 문서</b>는 암호를 알아도
 * 사용자조차 못 열고, <b>열기 암호</b>는 암호만 있으면 열리며, <b>배포용 문서</b>는 열쇠가 파일
 * 안에 있어 애초에 우리가 읽을 수 있다(실제로 읽고 있다). 셋을 한 갈래로 세면 배치 결과를 받고도
 * 무엇부터 손대야 할지 알 수 없다.
 *
 * <p>근거는 형식마다 다르다.
 *
 * <ul>
 *   <li><b>HWP 5.0</b> — FileHeader 속성 비트. 전체 파싱 없이 256바이트만 읽으면 된다.</li>
 *   <li><b>HWPX</b> — {@code META-INF/manifest.xml}의 {@code <odf:encryption-data>}. ODF 표준
 *       암호화라 키 유도가 PBKDF2(사용자 암호 기반)이며, <b>암호 없이는 복호화가 불가능</b>하다.
 *       hwpxlib에는 복호화 코드가 아예 없다.</li>
 * </ul>
 *
 * <p>근거를 얻지 못하면 {@link Kind#NONE}을 낸다 — 모르는 것을 아는 척해서 다른 갈래를 막는 쪽이
 * 더 나쁘다. 호출은 <b>실패한 파일에 대해서만</b> 일어나므로(→ {@link FailureClassifier})
 * 정상 파일이 많은 배치에 비용을 더하지 않는다.
 */
public record DocProtection(Kind kind, String detail) {

    /** 보호 없음을 나타내는 공용 인스턴스. */
    private static final DocProtection NONE = new DocProtection(Kind.NONE, "");

    /** 메시지에 나열할 암호화 항목 수 상한 — 목록이 길어도 로그 한 줄을 넘기지 않게 한다. */
    private static final int MAX_LISTED_ENTRIES = 6;

    /** 보호의 갈래. */
    public enum Kind {

        /** 보호가 없거나, 근거를 얻지 못했다. */
        NONE,

        /** 열기 암호 — 암호를 알면 열린다. */
        PASSWORD,

        /** DRM 보안 문서 — 암호를 알아도 권한 없이는 사용자도 못 연다. */
        DRM,

        /** 배포용 문서 — 열쇠가 파일 안에 있어 읽을 수 있다(hwplib이 복호화한다). */
        DISTRIBUTION
    }

    /**
     * 파일의 보호 상태를 판정한다.
     *
     * @param format {@link DocFormat#of}가 판정한 실제 형식 — 확장자가 아니라 내용 기준이다
     */
    public static DocProtection of(Path file, DocFormat format) {
        try {
            return switch (format) {
                case HWP5 -> ofHwp5(file);
                case HWPX -> ofHwpx(file);
                default -> NONE;
            };
        } catch (Exception | LinkageError e) {
            // 보호 판정에 실패했다고 분류 자체를 멈출 수는 없다 — 근거가 없을 뿐이다
            return NONE;
        }
    }

    /**
     * HWP 5.0 — FileHeader 속성 비트로 판정한다.
     *
     * <p>우선순위는 <b>DRM &gt; 암호 &gt; 배포용</b>이다. DRM이 걸린 문서는 암호를 알아도 열지
     * 못하므로, 둘 다 켜져 있으면 사용자가 실제로 부딪히는 벽인 DRM을 말해 줘야 한다.
     */
    private static DocProtection ofHwp5(Path file) throws Exception {
        FileHeader header = new FileHeader();
        try (InputStream in = Files.newInputStream(file)) {
            CompoundFileReader reader = new CompoundFileReader(in);
            try {
                StreamReader stream = reader.getChildStreamReader("FileHeader", false, null);
                ForFileHeader.read(header, stream);
            } finally {
                reader.close();
            }
        }

        if (header.isDRMDocument() || header.isPublicCertificationDRMDocument()
                || header.isEncryptPublicCertification()) {
            return new DocProtection(Kind.DRM,
                    "DRM 보안 문서입니다 — DRM이 해제된 사본이 필요합니다(권한 없이는 한글에서도 열리지 않습니다)");
        }
        if (header.hasPassword()) {
            return new DocProtection(Kind.PASSWORD,
                    "열기 암호가 설정된 문서입니다 — 암호 없이는 한글에서도 열리지 않습니다");
        }
        if (header.isDistribution()) {
            // 배포용은 원래 읽힌다(hwplib이 ViewText를 복호화한다). 그런데도 실패했다면
            // 암호 문제가 아니라 본문 구조를 해석하지 못한 것이다 — 대응이 완전히 다르다.
            return new DocProtection(Kind.DISTRIBUTION,
                    "배포용 문서인데 본문을 읽지 못했습니다"
                            + " — 배포용 자체는 지원하므로(대부분 정상 처리됩니다) 개별 확인이 필요합니다");
        }
        return NONE;
    }

    /**
     * HWPX — {@code META-INF/manifest.xml}이 선언한 암호화 정보로 판정한다.
     *
     * <p>ODF 표준 암호화라 알고리즘과 키 유도 방식이 매니페스트에 그대로 적혀 있다. 키 유도가
     * PBKDF2면 사용자 암호에서 키가 나온다는 뜻이라 <b>암호 없이는 방법이 없다</b>. 어떤 항목이
     * 잠겼는지까지 적어 두면 "미리보기라도 건질 수 있나"를 다시 열어 보지 않고 판단할 수 있다.
     */
    private static DocProtection ofHwpx(Path file) throws Exception {
        return ofHwpxManifest(readZipEntry(file, "META-INF/manifest.xml"));
    }

    /**
     * 이미 읽어 둔 {@code META-INF/manifest.xml} 본문으로 판정한다.
     *
     * <p>ZIP을 이미 열어 둔 호출부({@link HwpxScanDetector})가 컨테이너를 두 번 열지 않도록
     * 갈라 둔 진입점이다. 판별기가 <b>본문 XML을 파싱하기 전에</b> 이걸 먼저 보면, 암호화된
     * 바이트가 XML 파서로 들어가 {@code [Fatal Error] Invalid byte 2 of 2-byte UTF-8 sequence}가
     * 배치 로그에 흩뿌려지는 일도 없어진다.
     */
    public static DocProtection ofHwpxManifest(String manifest) throws Exception {
        if (manifest == null || !manifest.contains("encryption-data")) {
            return NONE;
        }

        Manifest parsed = parseManifest(manifest);
        if (parsed.encrypted.isEmpty()) {
            return NONE;
        }

        StringBuilder detail = new StringBuilder("HWPX 항목이 암호화돼 있습니다");
        if (!parsed.algorithms.isEmpty() || !parsed.derivations.isEmpty()) {
            detail.append('(')
                    .append(String.join(", ", parsed.algorithms))
                    .append(parsed.algorithms.isEmpty() || parsed.derivations.isEmpty() ? "" : " / ")
                    .append(String.join(", ", parsed.derivations))
                    .append(')');
        }
        detail.append(" — 암호를 알아야 열 수 있습니다. 암호화 항목: ").append(listed(parsed.encrypted));
        return new DocProtection(Kind.PASSWORD, detail.toString());
    }

    /** 매니페스트에서 뽑아 낸 암호화 선언 — 알고리즘·키 유도 방식·잠긴 항목 경로. */
    private record Manifest(List<String> encrypted, Set<String> algorithms, Set<String> derivations) {
    }

    /**
     * {@code manifest.xml}을 훑어 암호화가 선언된 항목과 알고리즘을 모은다.
     *
     * <p>{@code <odf:file-entry full-path="…">} 안에 {@code <odf:encryption-data>}가 들어 있는
     * 구조라, 현재 항목 경로를 들고 다니다가 암호화 선언을 만나면 확정한다.
     */
    private static Manifest parseManifest(String xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<String> encrypted = new ArrayList<>();
        Set<String> algorithms = new LinkedHashSet<>();
        Set<String> derivations = new LinkedHashSet<>();
        String current = null;

        XMLStreamReader reader = factory.createXMLStreamReader(new java.io.StringReader(xml));
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (reader.getLocalName()) {
                    case "file-entry" -> current = attribute(reader, "full-path");
                    case "encryption-data" -> {
                        if (current != null) {
                            encrypted.add(current);
                        }
                    }
                    case "algorithm" -> addLocalName(algorithms, attribute(reader, "algorithm-name"));
                    case "key-derivation" ->
                            addLocalName(derivations, attribute(reader, "key-derivation-name"));
                    default -> {
                        // 나머지 요소는 보호 판정과 무관하다
                    }
                }
            }
        } finally {
            reader.close();
        }
        return new Manifest(encrypted, algorithms, derivations);
    }

    /**
     * URI 형태의 알고리즘 이름에서 사람이 읽을 뒷부분만 남긴다
     * ({@code http://…#aes256-cbc} → {@code aes256-cbc}).
     */
    private static void addLocalName(Set<String> into, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        int cut = Math.max(value.lastIndexOf('#'), value.lastIndexOf('/'));
        into.add(cut < 0 ? value : value.substring(cut + 1));
    }

    /** 네임스페이스를 가리지 않고 속성 값을 찾는다(매니페스트마다 접두사가 다르다). */
    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** 항목 목록을 로그 한 줄에 맞게 줄여 적는다. */
    private static String listed(List<String> entries) {
        if (entries.size() <= MAX_LISTED_ENTRIES) {
            return String.join(", ", entries);
        }
        return String.join(", ", entries.subList(0, MAX_LISTED_ENTRIES))
                + " 외 " + (entries.size() - MAX_LISTED_ENTRIES) + "개";
    }

    /** ZIP에서 항목 하나만 읽는다. 없으면 null. */
    private static String readZipEntry(Path file, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
