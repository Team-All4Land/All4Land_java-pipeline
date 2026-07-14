package com.onnara.extract.db;

/** {@link DbLoader#loadAll} 결과 요약 — CLI 배치 요약 출력용. */
public record LoadStats(int filesOk, int filesFailed, int documentsInserted, int refFilesInserted) {
}
