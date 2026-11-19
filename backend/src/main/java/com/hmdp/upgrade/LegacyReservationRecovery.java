package com.hmdp.upgrade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Offline cutback helper. No legacy jobs or HTTP handlers are started here. */
public final class LegacyReservationRecovery {
    private LegacyReservationRecovery(){}
    public static int rebuild(JdbcTemplate db,Transactions tx,StringRedisTemplate redis){
        if(!"PAUSED".equals(db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class)))throw new Problem(409,"PAUSED_REQUIRED");
        if(db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE state='ACCEPTED'",Long.class)>0)throw new Problem(409,"DRAIN_ACCEPTED_BEFORE_ROLLBACK");
        var trading=new Trading(db,tx);var reservations=new Reservations(redis,db,trading,tx);int count=0;
        for(long activity:db.queryForList("SELECT id FROM ux_activity ORDER BY id",Long.class)){reservations.recover(activity,0);count++;}return count;
    }
}
