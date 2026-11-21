package com.nexvance.loginapp.core;

import java.util.List;
import java.util.Map;

/** Lists that stop at a limit say how many rows there are in all, so the screen can say when it shows only the first of them. */
final class Lists {

    private Lists() {}

    /** Puts {@code totalRows} on every row when the list was cut off; a list that holds everything is left as it is. */
    static List<Map<String, Object>> withTotal(List<Map<String, Object>> rows, long total) {
        if (total > rows.size()) {
            for (Map<String, Object> r : rows) {
                r.put("totalRows", total);
            }
        }
        return rows;
    }
}
