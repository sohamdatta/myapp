package com.example.loginapp.core;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /** Fills in the missing hashes, a batch at a time, across every Organization. Answers how many were filled. */
    public static int fill(Db db, Crypto crypto) {
        int total = 0;
        while (true) {
            int done = db.inOwner(c -> {
                record Row(UUID id, UUID tenant, byte[] sealed, String ifsc) {}
                List<Row> rows = new ArrayList<>();
                try (var st = c.prepareStatement("select id, tenant_id, account_enc, ifsc from app.bank_account where account_hash is null limit " + BATCH);
                        var rs = st.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3), rs.getString(4)));
                    }
                }
                for (Row r : rows) {
                    byte[] hash;
                    try {
                        hash = of(crypto, r.tenant(), r.ifsc(), crypto.decryptText(r.tenant(), r.sealed()));
                    } catch (IllegalStateException e) {
                        continue;   // sealed under a key this server does not hold; it cannot be fingerprinted here
                    }
                    try (var st = c.prepareStatement("update app.bank_account set account_hash = ? where id = ? and account_hash is null")) {
                        st.setBytes(1, hash);
                        st.setObject(2, r.id());
                        st.executeUpdate();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return rows.size();
            });
            total += done;
            if (done < BATCH) {
                return total;
            }
        }
    }
}
