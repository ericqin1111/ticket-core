package com.ticket.core.order.service;

import com.ticket.core.order.entity.TicketOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutSweepScheduler {

    private final OrderService orderService;

    @Value("${ticket.order.timeout-sweep.enabled:true}")
    private boolean enabled;

    @Value("${ticket.order.timeout-sweep.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${ticket.order.timeout-sweep.fixed-delay-ms:30000}")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        sweepOnce();
    }

    public void sweepOnce() {
        LocalDateTime now = LocalDateTime.now();
        String scanId = UUID.randomUUID().toString();
        List<TicketOrder> overdueOrders = orderService.findOverduePendingPaymentOrders(now, batchSize);
        log.info("Timeout sweep started: scanId={}, candidateCount={}", scanId, overdueOrders.size());

        for (TicketOrder order : overdueOrders) {
            boolean applied = orderService.timeoutCloseOrder(order.getOrderId(), now);
            log.info("Timeout sweep decision: scanId={}, orderId={}, decision={}",
                    scanId, order.getOrderId(), applied ? "CLOSED" : "SKIPPED");
        }
    }
}
