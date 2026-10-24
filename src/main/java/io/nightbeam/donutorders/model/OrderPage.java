package io.nightbeam.donutorders.model;

import java.util.List;

public record OrderPage(List<Order> orders, int page, int pages, int total) {
}
