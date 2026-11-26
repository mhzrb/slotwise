package com.slotwise.common.web;

import com.slotwise.common.exception.BadRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Restricts client-controlled sorting to an explicit set of properties.
 * Without this, a public endpoint could be asked to sort by an unrelated or
 * private attribute (e.g. a joined user's email), leaking information through
 * the result order, or hitting unindexed columns.
 */
public final class SortWhitelist {

    private SortWhitelist() {
    }

    public static Pageable restrict(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BadRequestException("INVALID_SORT_PROPERTY", "Cannot sort by '" + order.getProperty()
                        + "'. Allowed: " + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
        return pageable;
    }
}
