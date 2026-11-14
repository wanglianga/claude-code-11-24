package cc.royalty.repo;

import cc.royalty.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SettlementBatch> BATCH_MAPPER = (rs, n) -> new SettlementBatch(
            rs.getLong("id"), rs.getString("batch_no"), rs.getString("batch_type"),
            (Long) rs.getObject("platform_id"), rs.getDate("period_start").toLocalDate(),
            rs.getDate("period_end").toLocalDate(), rs.getString("status"),
            rs.getString("contract_snapshot_fingerprint"), rs.getString("report_scope"),
            rs.getString("locked_by"), rs.getString("approved_by"),
            rs.getObject("approved_at", java.time.OffsetDateTime.class), rs.getString("note"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("calculated_at", java.time.OffsetDateTime.class));

    private static final RowMapper<SettlementLine> LINE_MAPPER = (rs, n) -> new SettlementLine(
            rs.getLong("id"), rs.getLong("settlement_batch_id"), rs.getLong("report_line_id"),
            rs.getString("right_type"), (Long) rs.getObject("recording_id"),
            (Long) rs.getObject("work_id"), rs.getDate("usage_date").toLocalDate(),
            rs.getString("territory"), rs.getString("media"), rs.getString("allocation_scope"),
            rs.getLong("distributable_minor"), rs.getLong("fee_minor"), rs.getString("basis"),
            rs.getBigDecimal("rate_used"), rs.getString("chosen_contract_ids"),
            rs.getBoolean("conflict_flag"), rs.getString("conflict_detail"), rs.getString("status"),
            rs.getString("hold_reason"), rs.getString("source_fingerprint"), rs.getInt("segment_no"));

    private static final RowMapper<SettlementAllocation> ALLOC_MAPPER = (rs, n) -> new SettlementAllocation(
            rs.getLong("id"), rs.getLong("settlement_line_id"), (Long) rs.getObject("payee_id"),
            (Long) rs.getObject("contract_id"), rs.getString("alloc_status"),
            rs.getLong("share_numerator"), rs.getLong("share_denominator"), rs.getLong("amount_minor"));

    private static final RowMapper<Payable> PAYABLE_MAPPER = (rs, n) -> new Payable(
            rs.getLong("id"), rs.getLong("settlement_batch_id"), rs.getLong("payee_id"),
            rs.getString("right_type"), rs.getString("currency"), rs.getLong("amount_minor"),
            rs.getLong("paid_minor"), rs.getString("status"));

    // ---------- 范围锁（同一结算范围不可重复出账） ----------

    /** @return true 表示成功获得锁 */
    public boolean tryAcquireScopeLock(String scopeKey, long settlementBatchId, String actor) {
        try {
            jdbc.update("insert into settlement_lock(scope_key, settlement_batch_id, locked_by) values (?,?,?)",
                    scopeKey, settlementBatchId, actor);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseScopeLock(String scopeKey) {
        jdbc.update("delete from settlement_lock where scope_key = ?", scopeKey);
    }

    public Optional<Long> batchIdForScope(String scopeKey) {
        return jdbc.query("select settlement_batch_id from settlement_lock where scope_key=?",
                (rs, n) -> rs.getLong(1), scopeKey).stream().findFirst();
    }

    public void updateScopeLockBatch(String scopeKey, long batchId) {
        jdbc.update("update settlement_lock set settlement_batch_id=? where scope_key=?", batchId, scopeKey);
    }

    // ---------- 结算批次 ----------

    public long insertBatch(String batchNo, String batchType, Long platformId,
                            java.time.LocalDate from, java.time.LocalDate to,
                            String scopeKey, String actor, String reportScope) {
        return jdbc.queryForObject("insert into settlement_batch(batch_no, batch_type, platform_id, "
                + "period_start, period_end, status, report_scope, locked_by) "
                + "values (?,?,?,?,?, 'CREATED', ?, ?) returning id",
                Long.class, batchNo, batchType, platformId, from, to, reportScope, actor);
    }

    public void markCalculated(long batchId, String fingerprint) {
        jdbc.update("update settlement_batch set status='CALCULATED', calculated_at=now(), "
                + "contract_snapshot_fingerprint=? where id=?", fingerprint, batchId);
    }

    public void updateBatchNo(long batchId, String batchNo) {
        jdbc.update("update settlement_batch set batch_no=? where id=?", batchNo, batchId);
    }

    public void updateStatus(long batchId, String status) {
        jdbc.update("update settlement_batch set status=? where id=?", status, batchId);
    }

    public void approve(long batchId, String actor) {
        jdbc.update("update settlement_batch set status='APPROVED', approved_by=?, approved_at=now() where id=?",
                actor, batchId);
    }

    public Optional<SettlementBatch> findBatchByNo(String no) {
        return jdbc.query("select * from settlement_batch where batch_no=?", BATCH_MAPPER, no)
                .stream().findFirst();
    }

    public Optional<SettlementBatch> findBatchById(long id) {
        return jdbc.query("select * from settlement_batch where id=?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    public List<SettlementBatch> allBatches() {
        return jdbc.query("select * from settlement_batch order by id", BATCH_MAPPER);
    }

    // ---------- 逐笔计算与分摊 ----------

    public long insertLine(SettlementLine l) {
        return jdbc.queryForObject("insert into settlement_line(settlement_batch_id, report_line_id, right_type, "
                + "recording_id, work_id, usage_date, territory, media, allocation_scope, distributable_minor, "
                + "fee_minor, basis, rate_used, chosen_contract_ids, conflict_flag, conflict_detail, status, "
                + "hold_reason, source_fingerprint, segment_no) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, l.settlementBatchId(), l.reportLineId(), l.rightType(), l.recordingId(),
                l.workId(), l.usageDate(), l.territory(), l.media(), l.allocationScope(),
                l.distributableMinor(), l.feeMinor(), l.basis(), l.rateUsed(), l.chosenContractIds(),
                l.conflictFlag(), l.conflictDetail(), l.status(), l.holdReason(),
                l.sourceFingerprint(), l.segmentNo());
    }

    public void insertAllocation(long settlementLineId, Long payeeId, Long contractId, String status,
                                 long num, long den, long amount) {
        jdbc.update("insert into settlement_allocation(settlement_line_id, payee_id, contract_id, alloc_status, "
                + "share_numerator, share_denominator, amount_minor) values (?,?,?,?,?,?,?)",
                settlementLineId, payeeId, contractId, status, num, den, amount);
    }

    public List<SettlementLine> linesOfBatch(long batchId) {
        return jdbc.query("select * from settlement_line where settlement_batch_id=? order by id",
                LINE_MAPPER, batchId);
    }

    public void updateChosenContracts(long lineId, String contractIds) {
        jdbc.update("update settlement_line set chosen_contract_ids=? where id=?", contractIds, lineId);
    }

    public Optional<SettlementLine> findLineById(long id) {
        return jdbc.query("select * from settlement_line where id=?", LINE_MAPPER, id)
                .stream().findFirst();
    }

    public List<SettlementAllocation> allocationsOfLine(long lineId) {
        return jdbc.query("select * from settlement_allocation where settlement_line_id=? order by id",
                ALLOC_MAPPER, lineId);
    }

    /** 已处理过的报告行（支持中断后按分段续算，跳过已处理分段） */
    public java.util.Set<Long> processedReportLines(long batchId) {
        return new java.util.HashSet<>(jdbc.query("select distinct report_line_id from settlement_line "
                + "where settlement_batch_id=?", (rs, n) -> rs.getLong(1), batchId));
    }

    /** 已在其他结算批次出现过的报告行：保证同一平台原行全系统最多出账一次 */
    public java.util.Set<Long> globallySettledLineIds(long excludeBatchId) {
        return new java.util.HashSet<>(jdbc.query(
                "select distinct report_line_id from settlement_line where settlement_batch_id <> ?",
                (rs, n) -> rs.getLong(1), excludeBatchId));
    }

    // ---------- 应付汇总 ----------

    public long upsertPayable(long batchId, long payeeId, String rightType, String currency, long amount) {
        return jdbc.queryForObject("""
                insert into payable(settlement_batch_id, payee_id, right_type, currency, amount_minor, status)
                values (?,?,?,?,?, 'CALCULATED')
                on conflict (settlement_batch_id, payee_id, right_type, currency)
                do update set amount_minor = payable.amount_minor + excluded.amount_minor
                returning id""", Long.class, batchId, payeeId, rightType, currency, amount);
    }

    public List<Payable> payablesOfBatch(long batchId) {
        return jdbc.query("select * from payable where settlement_batch_id=? order by id", PAYABLE_MAPPER, batchId);
    }

    public Optional<Payable> findPayableById(long id) {
        return jdbc.query("select * from payable where id=?", PAYABLE_MAPPER, id).stream().findFirst();
    }

    public void updatePayableStatus(long id, String status) {
        jdbc.update("update payable set status=? where id=?", status, id);
    }

    public void addPaidAmount(long payableId, long amount) {
        jdbc.update("update payable set paid_minor = paid_minor + ? where id = ?", amount, payableId);
    }
}
