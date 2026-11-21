package dev.pointledger.engine;

import dev.pointledger.util.Json;
import dev.pointledger.engine.Model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps parsed JSON to engine commands. Used by both the HTTP API and command-log replay. */
public final class Codec {
    private Codec() {}

    public static Campaign campaign(Map<String, Object> m) {
        return new Campaign(Json.str(m, "id"), Json.str(m, "sellerId"), (int) Json.num(m, "rateBp"),
                Json.strOr(m, "group", ""), Json.num(m, "perOrderCap"), Json.num(m, "budget"),
                Json.numOr(m, "minSubtotal", 0), Json.numOr(m, "startsAt", 0), Json.numOr(m, "endsAt", 0));
    }

    public static OrderRequest order(Map<String, Object> m) {
        Object raw = m.get("lines");
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("field 'lines' must be an array");
        if (list.size() > 200) throw new IllegalArgumentException("too many lines");
        List<Line> lines = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> l = Json.obj(o);
            lines.add(new Line(Json.str(l, "sku"), Json.str(l, "sellerId"), Json.num(l, "unitPriceInclTax"),
                    (int) Json.num(l, "qty"), (int) Json.numOr(l, "taxRatePct", 10)));
        }
        return new OrderRequest(Json.str(m, "orderId"), Json.str(m, "customerId"), lines);
    }
}
