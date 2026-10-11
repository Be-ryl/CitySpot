package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillLuaScriptTest {

    @Test
    void luaScriptResourceCanBeLoadedAndHandlesMissingStock() throws Exception {
        ClassPathResource resource = new ClassPathResource("seckill.lua");
        assertTrue(resource.exists());

        String script = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        assertTrue(script.contains("if not stock"));
        assertTrue(script.contains("SISMEMBER"));
        assertTrue(script.contains("XADD"));
    }

    @Test
    void mqReservationAndIdempotentCompensationScriptsCanBeLoaded() throws Exception {
        String reservation = StreamUtils.copyToString(
                new ClassPathResource("seckill_mq.lua").getInputStream(), StandardCharsets.UTF_8
        );
        String compensation = StreamUtils.copyToString(
                new ClassPathResource("seckill_mq_compensate.lua").getInputStream(), StandardCharsets.UTF_8
        );
        assertTrue(reservation.contains("SISMEMBER"));
        assertTrue(reservation.contains("INCRBY"));
        assertTrue(!reservation.contains("XADD"));
        assertTrue(compensation.contains("SREM"));
        assertTrue(compensation.contains("== 1"));
    }
}
