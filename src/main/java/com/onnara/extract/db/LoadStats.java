package com.onnara.extract.db;

/**
 * {@link DbLoader#loadAll} 결과 요약 — CLI 배치 요약 출력용.
 *
 * @param filesOk           적재에 성공한 파일 수
 * @param filesFailed       적재에 실패한 파일 수(세이브포인트 롤백된 파일)
 * @param documentsInserted 삽입된 documents 행 수(= 성공 파일의 레코드 총합)
 * @param refFilesInserted  삽입된 ref_files 행 수(= 경로가 있는 이미지 총합)
 */
public record LoadStats(int filesOk, int filesFailed, int documentsInserted, int refFilesInserted) {
}
