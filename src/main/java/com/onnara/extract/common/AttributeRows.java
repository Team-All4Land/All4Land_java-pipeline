package com.onnara.extract.common;

import com.onnara.extract.common.model.NoticeRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 표준 레코드에서 <b>실제로 적재되는 항목값 행</b>을 뽑아낸다.
 *
 * <p>이 판정이 한 곳에만 있어야 하는 이유: 적재하는 쪽({@code DbLoader})과 "한 줄도 못 넣었다"를
 * 판정하는 쪽({@code UnmappedReport})이 규칙을 따로 구현하면 둘이 조용히 어긋난다. 그러면
 * 리포트에는 안 잡히는데 DB에도 없는 첨부가 생기고, 그 손실은 사후에 발견할 방법이 없다.
 *
 * <p>레코드의 필드가 그대로 행이 되지 않는다. 두 가지가 빠지고 하나가 합쳐진다:
 * <ul>
 *   <li>문서 단위 메타({@code scope=attachment})는 {@code TB_ATCH_FILE} 컬럼으로 가거나
 *       아예 적재하지 않으므로 항목값 행이 아니다.</li>
 *   <li>기간 시작/종료는 개별로 넣지 않고 daterange 리터럴 한 행으로 합친다.</li>
 * </ul>
 */
public final class AttributeRows {

    /**
     * 항목값 행 1건.
     *
     * @param itemCd 표준항목코드({@code TB_NOTI_ITEM_VAL.ITEM_CD})
     * @param value  정규화된 값({@code ITEM_VAL})
     */
    public record Row(String itemCd, String value) {
    }

    /** 인스턴스화 방지 — 정적 판정 함수만 제공하는 유틸리티 클래스. */
    private AttributeRows() {
    }

    /**
     * 레코드 하나가 낳는 항목값 행을 순서대로 만든다.
     *
     * @return 적재 대상이 하나도 없으면 빈 목록
     */
    public static List<Row> of(NoticeRecord record) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            String itemCd = entry.getKey();
            if (isAttachmentScoped(itemCd) || isPeriodPart(itemCd)) {
                continue;
            }
            rows.add(new Row(itemCd, entry.getValue()));
        }
        String period = periodRange(record);
        if (period != null) {
            rows.add(new Row(Synonyms.WORK_PERIOD, period));
        }
        return rows;
    }

    /** 레코드 목록 전체가 낳는 항목값 행 수 — 0이면 이 첨부는 DB에 값을 한 줄도 남기지 못한다. */
    public static int countOf(List<NoticeRecord> records) {
        int total = 0;
        for (NoticeRecord record : records) {
            total += of(record).size();
        }
        return total;
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

    /** 문서 단위 메타인지 — 항목값 행이 아니라 첨부의 속성이다. */
    private static boolean isAttachmentScoped(String itemCd) {
        return Synonyms.field(itemCd).map(f -> !f.isAttribute()).orElse(false);
    }

    /** 기간 분리 파생 필드인지 — daterange 한 행으로 합쳐 넣으므로 개별로는 넣지 않는다. */
    private static boolean isPeriodPart(String itemCd) {
        return Synonyms.WORK_PERIOD_START.equals(itemCd)
                || Synonyms.WORK_PERIOD_END.equals(itemCd);
    }
}
