package com.ticket.core.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.reservation.entity.ReservationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReservationRecordMapper extends BaseMapper<ReservationRecord> {

    @Update("UPDATE reservation_record " +
            "SET status = 'EXPIRED', released_at = #{releasedAt}, version = version + 1, updated_at = NOW(3) " +
            "WHERE reservation_id = #{reservationId} AND status = 'CONSUMED' AND version = #{version}")
    int releaseConsumedReservation(@Param("reservationId") String reservationId,
                                   @Param("releasedAt") java.time.LocalDateTime releasedAt,
                                   @Param("version") long version);
}
