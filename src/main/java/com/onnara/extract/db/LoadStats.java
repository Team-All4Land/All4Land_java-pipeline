package com.onnara.extract.db;

/**
 * {@link DbLoader#loadAll} 결과 요약 — CLI 배치 요약 출력용.
 *
 * @param filesOk           적재에 성공한 파일 수
 * @param filesFailed       적재에 실패한 파일 수(세이브포인트 롤백된 파일)
 * @param filesSkipped      적재 제외 판정으로 건너뛴 파일 수 — 실패와 섞어 세면
 *                          "고쳐야 할 오류"와 "의도한 제외"가 구분되지 않는다
 * @param documentsInserted 삽입된 documents 행 수(= 성공 파일의 레코드 총합)
 * @param refFilesInserted  삽입된 ref_files 행 수(= 경로가 있는 이미지 총합)
 */
public record LoadStats(int filesOk, int filesFailed, int filesSkipped,
                        int documentsInserted, int refFilesInserted) {
}
