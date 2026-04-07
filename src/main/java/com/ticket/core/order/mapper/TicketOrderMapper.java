package com.ticket.core.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.order.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    @Select("SELECT order_id, external_trade_no, reservation_id, status, buyer_ref, contact_phone, " +
            "contact_email, submission_context_json, payment_deadline_at, confirmed_at, version, created_at, updated_at " +
            "FROM ticket_order WHERE external_trade_no = #{externalTradeNo} LIMIT 1")
    TicketOrder selectByExternalTradeNo(@Param("externalTradeNo") String externalTradeNo);

    @Update("UPDATE ticket_order " +
            "SET status = 'CONFIRMED', confirmed_at = #{confirmedAt}, version = version + 1, updated_at = NOW(3) " +
            "WHERE order_id = #{orderId} AND status = 'PENDING_PAYMENT' AND version = #{version}")
    int confirmPendingPayment(@Param("orderId") String orderId,
                              @Param("confirmedAt") LocalDateTime confirmedAt,
                              @Param("version") long version);
}
