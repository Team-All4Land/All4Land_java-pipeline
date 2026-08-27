package com.onnara.extract.common;

import com.onnara.extract.common.model.NoticeRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 표준 레코드에서 <b>실제로 적재되는 행</b>을 뽑아낸다 — 표준항목 행과 라벨 행 두 갈래로.
 *
 * <p>이 판정이 한 곳에만 있어야 하는 이유: 적재하는 쪽({@code DbLoader})과 "한 줄도 못 넣었다"를
 * 판정하는 쪽({@code UnmappedReport})이 규칙을 따로 구현하면 둘이 조용히 어긋난다. 그러면
 * 리포트에는 안 잡히는데 DB에도 없는 첨부가 생기고, 그 손실은 사후에 발견할 방법이 없다.
 *
 * <p>레코드의 필드가 그대로 행이 되지 않는다. 두 가지가 빠지고 하나가 합쳐진다:
 * <ul>
 *   <li>문서 단위 메타({@code scope=attachment})는 {@code OS_ATCH_FILE_DTL} 컬럼으로 가거나
 *       아예 적재하지 않으므로 항목값 행이 아니다.</li>
 *   <li>기간 시작/종료는 개별로 넣지 않고 daterange 리터럴 한 행으로 합친다.</li>
 * </ul>
 *
 * <p><b>사전에 없는 것은 버리지 않고 라벨 행으로 돌린다.</b> 표준항목으로 매핑되지 못한 라벨
 * ({@code extras})과, 사전에 없는 항목코드가 여기 속한다. 뒤쪽은 {@code map}과 {@code load}를
 * 따로 돌릴 때 생긴다 — {@code NoticeRecord.fromJson}이 JSON 키를 검증 없이 항목코드로 받으므로
 * 옛 산출물이 옛 코드를 들고 들어온다. 그것을 표준항목 행으로 만들면 {@code OS_NOTI_ITEM_TC} FK를
 * 위반하고, 배치 삽입이라 그 첨부의 항목값·이미지·첨부 행이 통째로 롤백된다.
 */
public final class AttributeRows {

    /**
     * 표준항목 행 1건.
     *
     * @param itemCd 표준항목코드({@code OS_NOTI_ITEM_VAL_DTL.NOTI_ITEM_CD})
     * @param value  정규화된 값({@code ITEM_VAL_CTNT})
     */
    public record Row(String itemCd, String value) {
    }

    /**
     * 라벨 행 1건 — 표준항목이 아닌 값.
     *
     * @param itemLblNm 라벨 원문({@code OS_NOTI_LBL_VAL_DTL.ITEM_LBL_NM})
     * @param value     원문 값({@code ITEM_VAL_CTNT}) — 값 유형을 모르므로 정규화하지 않는다
     */
    public record LabelRow(String itemLblNm, String value) {
    }

    /**
     * 레코드 하나가 낳는 행 전부.
     *
     * @param items  {@code OS_NOTI_ITEM_VAL_DTL}에 들어갈 표준항목 행
     * @param labels {@code OS_NOTI_LBL_VAL_DTL}에 들어갈 라벨 행
     */
    public record Split(List<Row> items, List<LabelRow> labels) {
    }

    /** 인스턴스화 방지 — 정적 판정 함수만 제공하는 유틸리티 클래스. */
    private AttributeRows() {
    }

    /**
     * 레코드 하나가 낳는 행을 순서대로 만든다.
     *
     * @return 적재 대상이 하나도 없으면 양쪽 모두 빈 목록
     */
    public static Split of(NoticeRecord record) {
        List<Row> items = new ArrayList<>();
        // 라벨은 맵으로 모은다 — OS_NOTI_LBL_VAL_DTL의 PK가 (첨부, 처분, 라벨)이라 한 레코드 안에서
        // 라벨이 겹치면 그대로 PK 위반이 된다. 먼저 온 값을 지키는 것은 extras와 같은 규칙이다.
        Map<String, String> labels = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            String itemCd = entry.getKey();
            String value = entry.getValue();
            if (isPeriodPart(itemCd)) {
                // 사전에 없는 파생 필드다 — 아래 사전 조회보다 먼저 걸러야 라벨로 새지 않는다.
                continue;
            }
            Optional<Synonyms.FieldSpec> field = Synonyms.field(itemCd);
            if (field.isEmpty()) {
                put(labels, itemCd, value);
            } else if (field.get().isAttribute()) {
                add(items, itemCd, value);
            }
        }

        String period = periodRange(record);
        if (period != null) {
            items.add(new Row(Synonyms.WORK_PRD, period));
        }

        if (record.extras() != null) {
            record.extras().forEach((label, value) -> put(labels, label, value));
        }

        List<LabelRow> labelRows = new ArrayList<>(labels.size());
        labels.forEach((label, value) -> labelRows.add(new LabelRow(label, value)));
        return new Split(items, labelRows);
    }

    /**
     * 레코드 목록 전체가 낳는 <b>표준항목</b> 행 수 — 0이면 이 첨부는 표준항목을 하나도 뽑지
     * 못했다는 뜻이다.
     *
     * <p>라벨 행은 세지 않는다. 이 값을 보는 {@code UnmappedReport}가 묻는 것이 "사전이 이
     * 문서를 못 읽었는가"이고, 라벨 행이 있다는 것은 오히려 그 물음에 그렇다고 답하는 쪽이다.
     */
    public static int countOf(List<NoticeRecord> records) {
        int total = 0;
        for (NoticeRecord record : records) {
            total += of(record).items().size();
        }
        return total;
    }

    /** 값이 있는 것만 담는다 — ITEM_VAL_CTNT가 NOT NULL이다. */
    private static void add(List<Row> rows, String itemCd, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(new Row(itemCd, value));
        }
    }

    /** 값이 있는 것만, 먼저 온 것을 지키며 담는다. */
    private static void put(Map<String, String> labels, String label, String value) {
        if (label != null && !label.isBlank() && value != null && !value.isBlank()) {
            labels.putIfAbsent(label.strip(), value);
        }
    }

    /**
     * 기간 시작/종료를 PostgreSQL daterange 리터럴로 합친다.
     *
     * <p>둘 중 하나라도 없으면 null — 반쪽짜리 범위를 넣느니 항목을 비우고, 원문은
     * Mapper가 이미 extras에 남겨 뒀다. 양끝을 포함하는 닫힌 구간으로 적는다(점용 기간의
     * 종료일은 그날까지 쓸 수 있다는 뜻이다).
     *
     * <p><b>뒤집힌 구간도 null이다.</b> 실입력에 {@code [2008-05-30,2008-05-29]}처럼 종료일이
     * 시작일보다 하루 앞선 값이 나온다(연도를 잘못 읽은 "1년" 표기가 원인이다). 그대로 넣으면
     * daterange GiST 인덱스 표현식이 "하한값은 상한값과 같거나 작아야 합니다"로 거절하는데,
     * 배치 삽입이라 <b>그 첨부의 모든 항목값이 함께 롤백된다</b> — 기간 한 줄 때문에 문서 전체가
     * DB에서 사라진다. 실측 6건이 그렇게 통째로 빠져 있었다.
     *
     * <p>ISO 고정폭 표기는 사전순 비교가 곧 시간순 비교라 문자열로 견줘도 된다.
     */
    private static String periodRange(NoticeRecord record) {
        String start = record.workPeriodStart();
        String end = record.workPeriodEnd();
        if (start == null || end == null || end.compareTo(start) < 0) {
            return null;
        }
        return "[" + start + "," + end + "]";
    }

    /** 기간 분리 파생 필드인지 — daterange 한 행으로 합쳐 넣으므로 개별로는 넣지 않는다. */
    private static boolean isPeriodPart(String itemCd) {
        return Synonyms.WORK_PRD_ST.equals(itemCd)
                || Synonyms.WORK_PRD_EN.equals(itemCd);
    }
}
