package com.onnara.extract.db;

import com.onnara.extract.common.DbStandard;
import com.onnara.extract.common.NoticeTypes;
import com.onnara.extract.common.NoticeItems;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 코드 쪽 사전을 DB 레퍼런스 테이블로 동기화한다.
 *
 * <p>{@code notice_items.json} → {@code OS_NOTI_ITEM_TC}, {@code notice_types.json} →
 * {@code OS_NOTI_KND_TC}. 정의처는 항상 코드 쪽이고 DB는 그 사본이다 — 파이프라인을 돌릴 때마다
 * 없으면 넣고 달라졌으면 갱신한다.
 *
 * <p><b>사전에서 사라진 항목은 지우지 않는다.</b> 이미 적재된 {@code OS_NOTI_ITEM_VAL_DTL} 행이
 * FK로 붙어 있어 지우면 참조가 깨지고, 사전 축소의 의도는 "앞으로 이 항목으로 적재하지 말라"이지
 * "이미 적재한 값을 버려라"가 아니다. 남은 행은 다음 통계 회차에서 드러난다.
 */
public final class ReferenceSync {

    /** 표준항목 upsert — 이름·계열·값 형식·주요 여부는 사전이 바뀌면 따라 바뀐다. */
    private static final String UPSERT_ATTRIBUTE = """
            INSERT INTO OS_NOTI_ITEM_TC (NOTI_ITEM_CD, NOTI_ITEM_NM, ITEM_SRS_NM, ITEM_VAL_TY_CD, CORE_ITEM_YN)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (NOTI_ITEM_CD) DO UPDATE SET
                NOTI_ITEM_NM = EXCLUDED.NOTI_ITEM_NM, ITEM_SRS_NM = EXCLUDED.ITEM_SRS_NM,
                ITEM_VAL_TY_CD = EXCLUDED.ITEM_VAL_TY_CD, CORE_ITEM_YN = EXCLUDED.CORE_ITEM_YN
            """;

    /** 공고종류 upsert. */
    private static final String UPSERT_NOTICE_TYPE = """
            INSERT INTO OS_NOTI_KND_TC (NOTI_KND_CD, NOTI_KND_NM, HRNK_NOTI_KND_NM)
            VALUES (?, ?, ?)
            ON CONFLICT (NOTI_KND_CD) DO UPDATE SET
                NOTI_KND_NM = EXCLUDED.NOTI_KND_NM, HRNK_NOTI_KND_NM = EXCLUDED.HRNK_NOTI_KND_NM
            """;

    /** 인스턴스화 방지 — 정적 동기화 함수만 제공하는 유틸리티 클래스. */
    private ReferenceSync() {
    }

    /**
     * 사전을 레퍼런스 테이블에 반영한다. 마이그레이션 직후, 적재 전에 호출해야 한다 —
     * {@code OS_NOTI_ITEM_VAL_DTL}이 {@code OS_NOTI_ITEM_TC}을 참조하므로 순서가 뒤바뀌면
     * 첫 적재가 FK 위반으로 실패한다.
     *
     * @return 반영한 (표준항목 수, 공고종류 수)
     */
    public static Counts sync(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int attributes = syncAttributes(conn);
                int types = syncNoticeTypes(conn);
                conn.commit();
                return new Counts(attributes, types);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    /** scope=attribute인 사전 필드만 OS_NOTI_ITEM_TC으로 보낸다 — 문서 메타는 OS_ATCH_FILE_DTL 컬럼이다. */
    private static int syncAttributes(Connection conn) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_ATTRIBUTE)) {
            for (NoticeItems.FieldSpec field : NoticeItems.fields()) {
                if (!field.isAttribute()) {
                    continue;
                }
                ps.setString(1, field.itemCd());
                ps.setString(2, field.itemNm());
                if (field.srsNm() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, field.srsNm());
                }
                ps.setString(4, field.valTyCd());
                ps.setString(5, DbStandard.yn(field.coreYn()));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    /** 공고종류를 OS_NOTI_KND_TC로 보낸다. */
    private static int syncNoticeTypes(Connection conn) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_NOTICE_TYPE)) {
            for (NoticeTypes.Spec type : NoticeTypes.all()) {
                ps.setString(1, type.notiKndCd());
                ps.setString(2, type.notiKndNm());
                ps.setString(3, type.upperKndNm());
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    /**
     * 동기화 결과 건수.
     *
     * @param attributes OS_NOTI_ITEM_TC에 반영한 표준항목 수
     * @param noticeTypes OS_NOTI_KND_TC에 반영한 공고종류 수
     */
    public record Counts(int attributes, int noticeTypes) {
    }
}
