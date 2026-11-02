package com.example.loginapp.core;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.LoggerFactory;

/**
 * The fingerprint kept beside each bank account: an HMAC of the bank (the first four letters of the IFSC) and the account number,
 * within one Organization. Accounts are numbered within a bank, not a branch, so the branch is left out. New rows get theirs when they
 * are written; {@link #fill} gives one to the rows made before the column existed, and any that slip through.
 */
public final class BankHashes {

    private static final int BATCH = 500;

    private BankHashes() {}

    /** What is hashed: the bank and the number. */
    static String key(String ifsc, String account) {
        return ifsc.substring(0, 4) + "|" + account;
    }

    static byte[] of(Crypto crypto, UUID tenantId, String ifsc, String account) {
        return crypto.fingerprint(tenantId, "bank", key(ifsc, account));
    }

    /**
     * Fills in the missing hashes, a batch at a time, across every Organization, going through the rows once by id so that rows which
     * cannot be fingerprinted here (sealed under a key this server does not hold) are passed over, not asked for again. Answers how many
     * were filled.
     */
    public static int fill(Db db, Crypto crypto) {
        return fill(db, crypto, BATCH);
    }

    static int fill(Db db, Crypto crypto, int batch) {
        int filled = 0;
        int skipped = 0;
        UUID after = new UUID(0L, 0L);
        while (true) {
            UUID from = after;
            Page page = db.inOwner(c -> {
                record Row(UUID id, UUID tenant, byte[] sealed, String ifsc) {}
                List<Row> rows = new ArrayList<>();
                try (var st = c.prepareStatement("select id, tenant_id, account_enc, ifsc from app.bank_account where account_hash is null and id > ? "
                        + "order by id limit " + batch)) {
                    st.setObject(1, from);
                    try (var rs = st.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3), rs.getString(4)));
                        }
                    }
                }
                int done = 0;
                try (var st = c.prepareStatement("update app.bank_account set account_hash = ? where id = ? and account_hash is null")) {
                    for (Row r : rows) {
                        try {
                            st.setBytes(1, of(crypto, r.tenant(), r.ifsc(), crypto.decryptText(r.tenant(), r.sealed())));
                        } catch (IllegalStateException e) {
                            continue;   // sealed under a key this server does not hold; it cannot be fingerprinted here
                        }
                        st.setObject(2, r.id());
                        st.addBatch();
                        done++;
                    }
                    st.executeBatch();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                return new Page(rows.size(), done, rows.isEmpty() ? from : rows.get(rows.size() - 1).id());
            });
            filled += page.done();
            skipped += page.read() - page.done();
            if (page.read() < batch) {
                if (skipped > 0) {
                    LoggerFactory.getLogger(BankHashes.class).warn("{} bank accounts could not be fingerprinted (sealed under a key not held here)", skipped);
                }
                return filled;
            }
            after = page.last();
        }
    }

    /** One page: rows read, rows fingerprinted, and the last id read (where the next page starts). */
    private record Page(int read, int done, UUID last) {}
}
