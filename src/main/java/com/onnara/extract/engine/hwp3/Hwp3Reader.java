package com.onnara.extract.engine.hwp3;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 한글 3.0(HWP Document File V3.00) 바이너리 리더.
 *
 * <p>hwplib은 HWP 5.0(OLE 복합문서)만 읽는다. 한글 3.0은 컨테이너 자체가 다른 자체 포맷이라
 * 별도 파서가 필요하다. 이 클래스는 문단·표·캡션·임베디드 이미지를 읽어
 * {@link Hwp3Document}로 낸다.
 *
 * <p>레이아웃은 LibreOffice {@code hwpfilter}(한글 3.0의 유일한 공개 레퍼런스 구현,
 * MPL-2.0 / Apache-2.0)의 {@code hwpfile.cxx}·{@code hinfo.cxx}·{@code hpara.cxx}·
 * {@code hwpread.cxx}를 옮긴 것이다.
 *
 * <pre>
 * 0    파일 인식 정보 30바이트  "HWP Document File V3.00 \x1A\x01\x02\x03\x04\x05"
 * 30   문서 정보 128바이트  (+96 암호 / +124 압축 / +126 정보 블록 길이)
 * 158  문서 요약 1008바이트 + 정보 블록
 *      ── 압축 문서는 여기서부터 끝까지 raw deflate ──
 *      글꼴 → 스타일 → 문단 리스트 → 파일 태그(임베디드 이미지)
 * </pre>
 *
 * <p><b>이 파서의 유일한 함정은 문단 리스트의 종료 규칙이다.</b> 리스트의 끝은
 * {@code nch == 0}인 빈 문단인데, 그 빈 문단도 헤더 12바이트와 대표 글자모양 31바이트를
 * <b>소비한다</b>. 되감으면 43바이트가 어긋나 그 뒤 문서 전체가 깨진다. 표 셀·캡션마다
 * 중첩 문단 리스트가 있어 한 번만 어긋나도 복구되지 않는다.
 */
public final class Hwp3Reader {

    /** 파일 인식 정보(서명) 길이. */
    private static final int SIGNATURE_LENGTH = 30;

    /** 한글 3.0 서명 앞부분 — 버전 표기(V3.00/V2.x)는 뒤에 붙는다. */
    private static final byte[] SIGNATURE =
            "HWP Document File".getBytes(StandardCharsets.US_ASCII);

    /** 문서 정보 블록 길이. */
    private static final int DOC_INFO_LENGTH = 128;

    /** 문서 정보 안의 암호 플래그 위치(u16). */
    private static final int DOC_INFO_ENCRYPTED = 96;

    /** 문서 정보 안의 압축 플래그 위치(u8). */
    private static final int DOC_INFO_COMPRESSED = 124;

    /** 문서 정보 안의 정보 블록 길이 위치(u16). */
    private static final int DOC_INFO_BLOCK_LENGTH = 126;

    /** 문서 요약 블록 길이 — 56 hchar × 9필드. */
    private static final int SUMMARY_LENGTH = 1008;

    /** 글꼴 이름 하나의 길이. */
    private static final int FONT_NAME_LENGTH = 40;

    /** 글꼴 목록의 언어 수. */
    private static final int FONT_LANGUAGES = 7;

    /** 글자모양 구조체 길이. */
    private static final int CHAR_SHAPE_LENGTH = 31;

    /** 문단모양 구조체 길이 — 탭 40개(각 4바이트)를 포함한다. */
    private static final int PARA_SHAPE_LENGTH = 187;

    /** 스타일 이름 길이. */
    private static final int STYLE_NAME_LENGTH = 20;

    /** 줄 정보 구조체 길이 — u16 7개. */
    private static final int LINE_INFO_LENGTH = 14;

    /** 표 셀 구조체 길이. */
    private static final int CELL_LENGTH = 27;

    /** 그림·표 공통 위치 정보(FBox) 길이 — anchor부터 pgno까지. */
    private static final int FBOX_COMMON_LENGTH = 60;

    /** 임베디드 이미지 파일 태그. */
    private static final int TAG_EMBEDDED_PICTURE = 1;

    /** 파일 태그 영역의 종료 표시. */
    private static final int TAG_END = 0;

    /** 임베디드 이미지 태그의 이름·형식 필드 길이(각각). */
    private static final int TAG_PICTURE_NAME_LENGTH = 16;

    /** 표 하나가 가질 수 있는 셀 수 상한 — 손상 파일이 힙을 다 먹지 않게 한다. */
    private static final int MAX_CELLS = 4096;

    /** 중첩 문단 리스트 깊이 상한 — 순환 구조로 스택이 터지지 않게 한다. */
    private static final int MAX_DEPTH = 32;

    /**
     * 글자 하나가 차지하는 "글자 수"({@code hbox.cxx}의 {@code HBox::WSize}).
     * 제어 문자는 실제 바이트 길이와 무관하게 이 값만큼 문단의 글자 수를 소비한다.
     */
    private static final int[] CONTROL_WIDTHS = {
            1, 4, 4, 4, 4, 4, 4, 42, 48, 4, 4, 4, 4, 1, 4, 4,
            4, 4, 4, 4, 4, 4, 12, 5, 3, 3, 123, 4, 32, 4, 2, 2};

    /** 본문 바이트와 현재 위치. */
    private final byte[] data;

    /** 다음에 읽을 위치. */
    private int pos;

    /** 문서 등장 순서대로 쌓이는 본문 항목. */
    private final List<Hwp3Document.Item> items = new ArrayList<>();

    /** 파일 태그 영역에서 읽은 임베디드 이미지. */
    private final List<Hwp3Document.Image> images = new ArrayList<>();

    /** 본문 바이트를 지정해 리더를 만든다. */
    private Hwp3Reader(byte[] data) {
        this.data = data;
    }

    /**
     * 한글 3.0 파일을 읽는다.
     *
     * @throws IOException 서명이 다르거나, 암호가 걸렸거나, 구조를 읽다 깨진 경우
     */
    public static Hwp3Document read(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (!startsWith(all, SIGNATURE)) {
            throw new IOException("한글 3.0 서명이 아닙니다: " + file);
        }
        if (all.length < SIGNATURE_LENGTH + DOC_INFO_LENGTH) {
            throw new IOException("문서 정보 블록이 잘렸습니다: " + file);
        }

        int infoAt = SIGNATURE_LENGTH;
        if (u16(all, infoAt + DOC_INFO_ENCRYPTED) != 0) {
            // 암호 문서는 본문이 통째로 뒤섞여 있어 파싱해 볼 여지가 없다.
            // FailureClassifier가 이 문장을 보고 ENCRYPTED 갈래로 분류한다.
            throw new IOException("암호가 걸린 한글 3.0 문서입니다: " + file);
        }
        boolean compressed = (all[infoAt + DOC_INFO_COMPRESSED] & 0xFF) != 0;
        int infoBlockLength = u16(all, infoAt + DOC_INFO_BLOCK_LENGTH);

        // 문서 요약·정보 블록까지는 항상 원문 그대로다. 압축은 그 뒤부터 시작한다.
        int bodyAt = infoAt + DOC_INFO_LENGTH + SUMMARY_LENGTH + infoBlockLength;
        if (bodyAt > all.length) {
            throw new IOException("문서 요약·정보 블록이 잘렸습니다: " + file);
        }
        byte[] body = compressed
                ? inflate(all, bodyAt, file)
                : java.util.Arrays.copyOfRange(all, bodyAt, all.length);

        Hwp3Reader reader = new Hwp3Reader(body);
        try {
            reader.readBody();
        } catch (EOFException e) {
            throw new IOException("한글 3.0 본문이 잘렸습니다: " + file, e);
        }
        return new Hwp3Document(List.copyOf(reader.items), List.copyOf(reader.images));
    }

    /** 압축 본문을 푼다 — zlib 헤더 없는 raw deflate다. */
    private static byte[] inflate(byte[] all, int from, Path file) throws IOException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(all, from, all.length - from);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, all.length * 2));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    // 더 넣을 입력도, 낼 출력도 없다 — 잘린 스트림이다
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("한글 3.0 압축 본문을 풀 수 없습니다: " + file, e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** 글꼴·스타일을 건너뛰고 문단 리스트와 파일 태그를 읽는다. */
    private void readBody() throws IOException {
        skipFonts();
        skipStyles();
        items.addAll(readParaList(0));
        readTags();
    }

    /** 글꼴 목록 — 언어별로 개수를 읽고 이름 배열을 건너뛴다. */
    private void skipFonts() throws IOException {
        for (int lang = 0; lang < FONT_LANGUAGES; lang++) {
            int count = u16();
            skip((long) count * FONT_NAME_LENGTH);
        }
    }

    /** 스타일 목록 — 개수를 읽고 이름 + 글자모양 + 문단모양 배열을 건너뛴다. */
    private void skipStyles() throws IOException {
        int count = u16();
        skip((long) count * (STYLE_NAME_LENGTH + CHAR_SHAPE_LENGTH + PARA_SHAPE_LENGTH));
    }

    /**
     * 문단 리스트를 끝까지 읽는다.
     *
     * <p>리스트의 끝은 {@code nch == 0}인 빈 문단이며, {@link #readPara}가 그 43바이트를
     * 소비한 뒤 null을 돌려준다 — 되감지 않는 것이 규칙이다.
     */
    private List<Hwp3Document.Item> readParaList(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("문단 리스트 중첩이 너무 깊습니다(깊이 " + depth + ")");
        }
        List<Hwp3Document.Item> collected = new ArrayList<>();
        while (true) {
            List<Hwp3Document.Item> para = readPara(depth);
            if (para == null) {
                return collected;
            }
            collected.addAll(para);
        }
    }

    /**
     * 문단 하나를 읽는다. 리스트의 끝(빈 문단)이면 null.
     *
     * <p>반환 목록은 이 문단이 낳은 본문 항목들이다 — 글자 스트림에 표가 끼어 있으면
     * "앞 텍스트 → 표 → 캡션 → 뒤 텍스트" 순으로 여러 항목이 나온다.
     */
    private List<Hwp3Document.Item> readPara(int depth) throws IOException {
        if (remaining() < 12 + CHAR_SHAPE_LENGTH) {
            // 남은 바이트가 빈 문단 하나에도 못 미친다 — 리스트가 끝난 것으로 본다
            pos = data.length;
            return null;
        }
        int reuseShape = u8();
        int charCount = u16();
        int lineCount = u16();
        int containCharShape = u8();
        u8();                                    // etcflag — 쪽나눔 플래그, 텍스트에 영향 없음
        u32();                                   // ctrlflag
        u8();                                    // pstyno

        // 대표 글자모양은 빈 문단(리스트 종료 표시)에도 붙어 있다 — 반드시 소비해야 한다
        skip(CHAR_SHAPE_LENGTH);
        if (charCount == 0) {
            return null;
        }
        if (reuseShape == 0) {
            skip(PARA_SHAPE_LENGTH);
        }
        skip((long) lineCount * LINE_INFO_LENGTH);
        if (containCharShape != 0) {
            for (int i = 0; i < charCount; i++) {
                if (u8() == 0) {
                    skip(CHAR_SHAPE_LENGTH);
                }
            }
        }
        return readCharStream(charCount, depth);
    }

    /**
     * 문단의 글자 스트림을 읽어 텍스트와 표를 순서대로 낸다.
     *
     * <p>{@code charCount}는 글자 수이지 바이트 수가 아니다. 제어 문자는
     * {@link #CONTROL_WIDTHS}만큼 글자 수를 소비하므로, 바이트 커서와 글자 카운터가
     * 따로 논다.
     */
    private List<Hwp3Document.Item> readCharStream(int charCount, int depth) throws IOException {
        List<Hwp3Document.Item> collected = new ArrayList<>();
        int[] codes = new int[charCount];
        int textLength = 0;
        int consumed = 0;

        while (consumed < charCount) {
            int code = u16();
            if (code > 31 || code == Hwp3Chars.CH_END_PARA) {
                if (code == Hwp3Chars.CH_END_PARA) {
                    break;
                }
                codes[textLength++] = code;
                consumed++;
                continue;
            }
            // 제어 문자 앞까지 쌓인 텍스트를 먼저 흘려보내야 표 앞뒤 순서가 보존된다
            textLength = flushText(collected, codes, textLength);
            readControl(code, collected, depth);
            consumed += CONTROL_WIDTHS[code];
        }
        flushText(collected, codes, textLength);
        return collected;
    }

    /** 쌓인 글자 코드를 텍스트 항목으로 흘려보내고 버퍼를 비운다(공백만이면 버린다). */
    private int flushText(List<Hwp3Document.Item> collected, int[] codes, int length) {
        if (length > 0) {
            String text = Hwp3Chars.decode(codes, length).trim();
            if (!text.isEmpty()) {
                collected.add(new Hwp3Document.Text(text));
            }
        }
        return 0;
    }

    /** 제어 문자 하나가 끌고 오는 데이터를 읽는다(본문이 되는 것은 표·캡션뿐이다). */
    private void readControl(int code, List<Hwp3Document.Item> collected, int depth)
            throws IOException {
        switch (code) {
            case 0, 1, 2, 3, 4, 12, 27, 29 -> skipDataBlock();
            case 5 -> readFieldCode();
            case 6 -> skipAfterLengthPrefix(34);          // 북마크
            case 7 -> skip(80 + 2);                       // 날짜 형식
            case 8 -> skip(80 + 12 + 2);                  // 날짜 코드
            case 9 -> skip(6);                            // 탭
            case 10 -> readTextBox(collected, depth);
            case 11 -> readPicture(collected, depth);
            case 14 -> skip(90);                          // 선
            // 숨은 설명·머리말/꼬리말·각주는 인쇄되는 본문이 아니다.
            // 커서를 맞추려면 문단 리스트를 끝까지 읽어야 하지만, 결과는 버린다.
            case 15 -> {
                skip(14);
                readParaList(depth + 1);
            }
            case 16 -> {
                skip(16);
                readParaList(depth + 1);
            }
            case 17 -> {
                skip(20);
                readParaList(depth + 1);
            }
            case 18, 19, 20, 21 -> skip(6);               // 자동 번호·쪽번호
            case 22 -> skip(22);                          // 메일 머지
            case 23 -> skip(8);                           // 글자 겹침
            case 24, 25 -> skip(4);                       // 하이픈·차례 표식
            case 26 -> skip(244);                         // 색인 표식
            case 28 -> skip(62);                          // 개요 번호
            case 30, 31 -> skip(2);                       // 묶음 빈칸·고정폭 빈칸
            default -> throw new IOException(
                    "알 수 없는 제어 문자 " + code + " (위치 " + (pos - 2) + ")");
        }
    }

    /** 길이 접두사가 붙은 블록을 통째로 건너뛴다. */
    private void skipDataBlock() throws IOException {
        long length = u32();
        u16();                                   // 종류 확인용 반복 코드
        skip(length);
    }

    /** 길이 접두사(u32) + 반복 코드(u16) 뒤에 고정 길이가 오는 블록. */
    private void skipAfterLengthPrefix(int fixedLength) throws IOException {
        u32();
        u16();
        skip(fixedLength);
    }

    /** 필드 코드 — 문자열 3개와 이진 데이터가 가변 길이로 붙는다. */
    private void readFieldCode() throws IOException {
        u32();                                   // 전체 크기
        u16();                                   // 반복 코드
        skip(2 + 4);                             // type, reserved1
        u16();                                   // location_info
        skip(22);                                // reserved2
        long[] lengths = {u32(), u32(), u32()};
        long binaryLength = u32();
        for (long length : lengths) {
            // 레퍼런스가 1024바이트로 자르고 나머지는 건너뛴다 — 커서 진행량은 원래 길이다
            long capped = Math.min(length, 1024) / 2 * 2;
            skip(capped);
            skip(length - capped);
        }
        skip(binaryLength);
    }

    /**
     * 글상자·표({@code CH_TEXT_BOX}) — {@code type == 0}이면 표다.
     *
     * <p>표가 아닌 글상자도 셀 구조는 같으므로 같은 경로로 읽되, 본문에는 셀 텍스트를
     * 문단으로 풀어 넣는다(글상자는 격자가 아니라 그냥 글이다).
     */
    private void readTextBox(List<Hwp3Document.Item> collected, int depth) throws IOException {
        skip(4);                                 // reserved
        expect(10);
        skip(8);                                 // cap_len, dummy1, next, dummy2
        skip(FBOX_COMMON_LENGTH);
        skip(10);                                // showpg, cap_pos, num, dummy3, baseline
        int type = u16();
        int cellCount = u16();
        u16();                                   // protect
        if (cellCount <= 0 || cellCount > MAX_CELLS) {
            throw new IOException("표 셀 수가 비정상입니다: " + cellCount);
        }

        int[][] geometry = new int[cellCount][4];
        for (int i = 0; i < cellCount; i++) {
            u16();                               // p
            u16();                               // color
            geometry[i][0] = u16();              // x
            geometry[i][1] = u16();              // y
            geometry[i][2] = u16();              // w
            geometry[i][3] = u16();              // h
            skip(CELL_LENGTH - 12);              // txthigh, cellhigh, 플래그·선종류·음영
        }

        List<String> cellTexts = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) {
            cellTexts.add(flatten(readParaList(depth + 1)));
        }
        List<Hwp3Document.Item> caption = readParaList(depth + 1);

        if (type == 0) {
            // 표 캡션은 표가 들고 간다 — 문단으로 흘려 보내면 어느 표의 것인지 알 수 없다
            collected.add(toTable(geometry, cellTexts, flatten(caption)));
            return;
        }
        for (String text : cellTexts) {
            if (!text.isEmpty()) {
                collected.add(new Hwp3Document.Text(text));
            }
        }
        // 글상자 캡션은 붙일 대상이 raw 계약에 없으므로 지금까지처럼 문단으로 남긴다
        collected.addAll(caption);
    }

    /**
     * 셀의 픽셀 좌표를 격자 색인으로 환산해 표를 만든다.
     *
     * <p>한글 3.0은 행·열 번호를 저장하지 않고 셀마다 좌표(x, y, w, h)만 남긴다.
     * 모든 셀의 경계값을 모아 정렬하면 그 목록에서의 색인이 곧 행·열 번호이고,
     * 끝 경계와의 색인 차가 병합 span이다(LibreOffice가 쓰는 방식과 같다).
     */
    private static Hwp3Document.Table toTable(int[][] geometry, List<String> texts, String caption) {
        TreeSet<Integer> xs = new TreeSet<>();
        TreeSet<Integer> ys = new TreeSet<>();
        for (int[] g : geometry) {
            xs.add(g[0]);
            xs.add(g[0] + g[2]);
            ys.add(g[1]);
            ys.add(g[1] + g[3]);
        }
        List<Integer> columns = new ArrayList<>(xs);
        List<Integer> rows = new ArrayList<>(ys);

        List<Hwp3Document.Cell> cells = new ArrayList<>(geometry.length);
        for (int i = 0; i < geometry.length; i++) {
            int[] g = geometry[i];
            int col = columns.indexOf(g[0]);
            int row = rows.indexOf(g[1]);
            int colSpan = Math.max(1, columns.indexOf(g[0] + g[2]) - col);
            int rowSpan = Math.max(1, rows.indexOf(g[1] + g[3]) - row);
            cells.add(new Hwp3Document.Cell(row, col, rowSpan, colSpan, texts.get(i)));
        }
        return new Hwp3Document.Table(
                Math.max(1, rows.size() - 1), Math.max(1, columns.size() - 1), cells,
                caption == null || caption.isBlank() ? null : caption.trim());
    }

    /** 그림 — 본체 데이터는 건너뛰고(임베디드 이미지는 파일 태그에 있다) 캡션만 살린다. */
    private void readPicture(List<Hwp3Document.Item> collected, int depth) throws IOException {
        skip(4);                                 // reserved
        expect(11);
        long followSize = u32();
        skip(4);                                 // dummy1, dummy2
        skip(FBOX_COMMON_LENGTH);
        skip(6);                                 // showpg, cap_pos, num
        skip(1);                                 // pictype
        skip(8);                                 // skip[2], scale[2]
        skip(256 + 9);                           // 그림 파일 경로, 밝기·대비 등
        skip(followSize);
        collected.addAll(readParaList(depth + 1));
    }

    /**
     * 파일 태그 영역 — 임베디드 이미지가 여기 있다.
     *
     * <p>{@code tag == 0}이 종료 표시다. 모르는 태그는 길이만큼 건너뛰므로,
     * 미리보기 이미지·하이퍼텍스트 같은 항목이 늘어도 파서가 깨지지 않는다.
     */
    private void readTags() {
        while (remaining() >= 8) {
            long tag;
            long size;
            try {
                tag = u32();
                size = u32();
            } catch (IOException e) {
                return;
            }
            if (tag == TAG_END) {
                return;
            }
            if (size <= 0) {
                continue;
            }
            if (size > remaining()) {
                return;                          // 잘린 태그 — 여기까지가 읽을 수 있는 전부다
            }
            try {
                if (tag == TAG_EMBEDDED_PICTURE && size > TAG_PICTURE_NAME_LENGTH * 2L) {
                    readEmbeddedPicture(size);
                } else {
                    skip(size);
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    /** 임베디드 이미지 한 건 — 이름 16바이트 + 형식 16바이트 + 원본 데이터. */
    private void readEmbeddedPicture(long size) throws IOException {
        String name = ascii(TAG_PICTURE_NAME_LENGTH);
        String format = ascii(TAG_PICTURE_NAME_LENGTH);
        int dataLength = (int) (size - TAG_PICTURE_NAME_LENGTH * 2L);
        byte[] bytes = bytes(dataLength);
        images.add(new Hwp3Document.Image(name, format, bytes));
    }

    /** 문단 항목들을 셀 텍스트 한 줄로 이어 붙인다(중첩 표 셀까지 평평하게 편다). */
    private static String flatten(List<Hwp3Document.Item> paragraphs) {
        StringBuilder out = new StringBuilder();
        for (Hwp3Document.Item item : paragraphs) {
            if (item instanceof Hwp3Document.Text text) {
                append(out, text.text());
            } else if (item instanceof Hwp3Document.Table table) {
                for (Hwp3Document.Cell cell : table.cells()) {
                    append(out, cell.text());
                }
            }
        }
        return out.toString();
    }

    /** 비어 있지 않은 조각을 공백으로 이어 붙인다. */
    private static void append(StringBuilder out, String piece) {
        if (piece == null || piece.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(piece);
    }

    /** 제어 문자 블록이 자기 종류를 한 번 더 적어 두는 자리를 확인한다 — 어긋나면 그 자리가 곧 실패다. */
    private void expect(int code) throws IOException {
        int actual = u16();
        if (actual != code) {
            throw new IOException(
                    "제어 문자 " + code + " 자리에 " + actual + "이 있습니다(위치 " + (pos - 2) + ")");
        }
    }

    /** 남은 바이트 수. */
    private int remaining() {
        return data.length - pos;
    }

    /** 부호 없는 1바이트. */
    private int u8() throws IOException {
        require(1);
        return data[pos++] & 0xFF;
    }

    /** 부호 없는 2바이트(리틀엔디안). */
    private int u16() throws IOException {
        require(2);
        int value = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        pos += 2;
        return value;
    }

    /** 부호 없는 4바이트(리틀엔디안). */
    private long u32() throws IOException {
        require(4);
        long value = (data[pos] & 0xFFL)
                | ((data[pos + 1] & 0xFFL) << 8)
                | ((data[pos + 2] & 0xFFL) << 16)
                | ((data[pos + 3] & 0xFFL) << 24);
        pos += 4;
        return value;
    }

    /** 바이트 배열을 잘라 낸다. */
    private byte[] bytes(int length) throws IOException {
        require(length);
        byte[] out = java.util.Arrays.copyOfRange(data, pos, pos + length);
        pos += length;
        return out;
    }

    /** 고정 길이 ASCII 필드 — 널 이후는 버린다. */
    private String ascii(int length) throws IOException {
        byte[] raw = bytes(length);
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII).trim();
    }

    /** 지정 바이트만큼 건너뛴다. */
    private void skip(long length) throws IOException {
        if (length < 0) {
            throw new IOException("길이가 음수입니다: " + length + " (위치 " + pos + ")");
        }
        require(length);
        pos += (int) length;
    }

    /** 남은 바이트가 부족하면 EOF로 끊는다. */
    private void require(long length) throws IOException {
        if (length > remaining()) {
            throw new EOFException("위치 " + pos + "에서 " + length + "바이트가 부족합니다");
        }
    }

    /** 부호 없는 2바이트(리틀엔디안)를 배열에서 직접 읽는다. */
    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    /** {@code data}가 {@code signature}로 시작하는지. */
    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
