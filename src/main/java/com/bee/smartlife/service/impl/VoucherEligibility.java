package com.bee.smartlife.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service
public class VoucherEligibility {
    public record Rule(boolean available,long beginSecond,long endSecond) {}
    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    public VoucherEligibility(JdbcTemplate jdbc,@Value("${smartlife.business-zone:Asia/Shanghai}") String zone){this.jdbc=jdbc;this.zone=ZoneId.of(zone);}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Rule rule(long id) {
        return jdbc.query("SELECT v.status,v.type,sv.begin_time,sv.end_time,p.state,p.version,p.published_version FROM tb_voucher v JOIN tb_seckill_voucher sv ON sv.voucher_id=v.id LEFT JOIN tb_voucher_publication p ON p.voucher_id=v.id WHERE v.id=?",
                (row,index)->{
                    boolean ready=row.getInt("status")==1&&row.getInt("type")==1&&"READY".equals(row.getString("state"))&&row.getLong("version")==row.getLong("published_version");
                    LocalDateTime begin=row.getObject("begin_time",LocalDateTime.class),end=row.getObject("end_time",LocalDateTime.class);
                    return new Rule(ready,begin.atZone(zone).toEpochSecond(),end.atZone(zone).toEpochSecond());
                },id).stream().findFirst().orElse(new Rule(false,0,0));
    }
}
