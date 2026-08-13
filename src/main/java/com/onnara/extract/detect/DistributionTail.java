package com.onnara.extract.detect;

import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DirectoryEntry;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DocumentEntry;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.DocumentInputStream;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.Entry;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.POIFSFileSystem;
import kr.dogfoot.hwplib.util.binary.Obfuscation;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.zip.Inflater;

/**
 * 배포용 HWP가 <b>hwplib의 마지막 블록 유실</b> 때문에 깨진 것인지 가려낸다 — 재저장으로 살릴 수
 * 있는 건과 정말 못 읽는 건을 나누는 자리다.
 *
 * <p>hwplib은 배포용 본문({@code ViewText/Section*})을 {@code AES/ECB/PKCS5Padding} +
 * {@code CipherInputStream}으로 읽는다. 그런데 배포용 본문은 PKCS5 패딩 규격이 아니라서 마지막
 * 16바이트 블록에서 {@code BadPaddingException}이 나고, {@code CipherInputStream}은 그 예외를
 * <b>조용히 삼키며 해당 블록을 버린다</b>.
 *
 * <p>버려진 블록이 압축 데이터의 여분이면 아무 일도 없다(그래서 대부분의 배포용 문서는 잘 읽힌다).
 * 실제 압축 데이터가 그 블록에 걸쳐 있으면 뒤쪽 레코드가 잘려, hwplib이 문단을 읽다 끊기고
 * {@code "This is not paragraph."}로 죽는다. 걸치느냐 마느냐는 압축 결과 길이가 16바이트 경계 어디에
 * 떨어지는지의 문제라 <b>문서의 성질이 아니라 운</b>이다.
 *
 * <p>판정 방법은 그 상황을 그대로 재현하는 것이다 — 본문 전체를 {@code NoPadding}으로 풀었을 때와
 * 마지막 블록을 뺀 채 풀었을 때의 길이를 견준다. 줄어들면 hwplib이 잃는 데이터가 실재한다는 뜻이다.
 *
 * <p>보유 문서 16,665건을 훑어 배포용 85건 중 1건이 여기 해당했고, 그 1건이 실제 추출 실패 건과
 * 정확히 일치했다. 드물지만 <b>사유를 정확히 말할 수 있는</b> 실패다 — 이 갈래로 떨어지면 원본을
 * 다시 받을 필요 없이 한글에서 다시 저장하기만 하면 된다.
 *
 * <p>근거를 얻지 못하면 {@code false}다. 재저장하면 된다고 잘못 안내하는 쪽이, 아무 말도 못 하는
 * 쪽보다 나쁘다 — 고칠 수 없는 파일을 계속 다시 저장해 보게 만든다.
 */
public final class DistributionTail {

    /** HWPTAG_BEGIN(0x010) + 12 — 배포용 문서 데이터 레코드. 본문 앞에 256바이트로 붙는다. */
    private static final int HWPTAG_DISTRIBUTE_DOC_DATA = 0x010 + 12;

    /** 배포용 데이터 레코드의 본문 길이(레코드 헤더 4바이트 제외). */
    private static final int DOC_DATA_SIZE = 256;

    /** 배포용 데이터 레코드 전체 길이 — 이 뒤부터가 암호문이다. */
    private static final int CIPHER_OFFSET = 4 + DOC_DATA_SIZE;

    /** AES 블록 크기 — CipherInputStream이 잃는 단위다. */
    private static final int BLOCK = 16;

    /** 인스턴스화 방지 — 판정 함수만 제공한다. */
    private DistributionTail() {
    }

    /**
     * 마지막 블록 유실로 본문이 잘리는 문서인지 판정한다.
     *
     * <p>섹션이 여럿이면 <b>하나라도</b> 잘리면 참이다 — 한 섹션만 잘려도 그 지점에서 파싱이 끝난다.
     *
     * @return 잘리는 것이 확인되면 true, 아니거나 판정하지 못하면 false(예외를 던지지 않는다)
     */
    public static boolean truncatesBody(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DirectoryEntry root = new POIFSFileSystem(in).getRoot();
            if (!root.hasEntry("ViewText")) {
                return false;
            }
            DirectoryEntry viewText = (DirectoryEntry) root.getEntry("ViewText");
            for (Iterator<Entry> it = viewText.getEntries(); it.hasNext(); ) {
                Entry entry = it.next();
                if (entry instanceof DocumentEntry doc
                        && doc.getName().startsWith("Section")
                        && truncatesSection(read(doc))) {
                    return true;
                }
            }
            return false;
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            // 판정 실패는 근거가 없다는 뜻일 뿐이다 — 진짜 원인을 가려서는 안 된다
            return false;
        }
    }

    /** 섹션 하나를 두 번 풀어 본다 — 전체로 한 번, 마지막 블록을 뺀 채로 한 번. */
    private static boolean truncatesSection(byte[] section) throws Exception {
        if (section.length < CIPHER_OFFSET + BLOCK) {
            return false;
        }
        int header = ByteBuffer.wrap(section, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if ((header & 0x3FF) != HWPTAG_DISTRIBUTE_DOC_DATA) {
            return false;
        }

        byte[] cipherText = Arrays.copyOfRange(section, CIPHER_OFFSET, section.length);
        if (cipherText.length % BLOCK != 0) {
            return false;
        }

        byte[] key = secretKey(section);
        byte[] whole = inflate(decrypt(cipherText, key));
        if (whole == null) {
            return false;                   // 복호화 근거를 못 얻었다 — 다른 원인이다
        }
        byte[] withoutLastBlock = inflate(decrypt(
                Arrays.copyOfRange(cipherText, 0, cipherText.length - BLOCK), key));
        int survived = withoutLastBlock == null ? 0 : withoutLastBlock.length;
        return survived < whole.length;
    }

    /**
     * 배포용 데이터 레코드에서 AES 키를 꺼낸다 — hwplib {@code StreamReader}와 같은 방식이다.
     *
     * <p>레코드 본문은 뒤섞여 있어 {@link Obfuscation#transform}으로 먼저 풀고, 첫 바이트가 가리키는
     * 자리에서 16바이트를 가져온다. 키 자체가 파일 안에 있어 <b>암호를 몰라도 열린다</b> — 배포용이
     * 사용자 암호 문서와 갈리는 지점이다.
     */
    private static byte[] secretKey(byte[] section) {
        byte[] docData = Arrays.copyOfRange(section, 4, CIPHER_OFFSET);
        Obfuscation.transform(docData);
        int offset = 4 + (docData[0] & 0x0F);
        return Arrays.copyOfRange(docData, offset, offset + BLOCK);
    }

    /**
     * 패딩 검사 없이 복호화한다.
     *
     * <p>{@code NoPadding}인 것이 핵심이다 — {@code PKCS5Padding}으로 열면 hwplib과 똑같이 마지막
     * 블록에서 걸려, 정작 재려는 <b>차이 자체가 사라진다</b>.
     */
    private static byte[] decrypt(byte[] cipherText, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(cipherText);
    }

    /**
     * raw deflate로 푼다. 입력이 중간에 끊겨 있으면 <b>거기까지</b> 풀어 돌려준다 —
     * 그 "거기까지"의 길이가 곧 hwplib이 건지는 양이라 판정의 근거가 된다.
     */
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    break;                  // 입력이 모자라 더 못 푼다
                }
                out.write(buf, 0, n);
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** OLE 스트림 하나를 통째로 읽는다. */
    private static byte[] read(DocumentEntry entry) throws Exception {
        try (DocumentInputStream in = new DocumentInputStream(entry)) {
            return in.readAllBytes();
        }
    }
}
