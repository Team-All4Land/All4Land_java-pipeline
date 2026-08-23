package com.onnara.extract.db;

/**
 * {@link DbLoader#loadAll} 결과 요약 — CLI 배치 요약 출력용.
 *
 * @param agenciesUpserted AGNCY_BAS에 반영한 기관 수 — 첨부가 0건인 폴더도 세므로
 *                         "긁긴 했는데 아무것도 못 건진 기관"이 요약에서 드러난다
 * @param filesOk          적재에 성공한 첨부 수
 * @param filesFailed      적재 자체가 실패한 첨부 수(세이브포인트 롤백된 건)
 * @param filesSkipped     적재 제외 판정으로 항목값을 넣지 않은 첨부 수 — 실패와 섞어 세면
 *                         "고쳐야 할 오류"와 "의도한 제외"가 구분되지 않는다
 * @param recordsInserted  삽입된 NOTI_ITEM_VAL_DTL 행 수(= 표준항목 값 총합)
 * @param imagesInserted   삽입된 ATCH_IMG_DTL 행 수(= 경로가 있는 이미지 총합)
 */
public record LoadStats(int agenciesUpserted, int filesOk, int filesFailed, int filesSkipped,
                        int recordsInserted, int imagesInserted) {
}
