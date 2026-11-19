package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import static org.junit.jupiter.api.Assertions.*;

class HistoryDeploymentTest {
    @Test void dockerInitializationMatchesTheFixedTopologyAndSchema() throws Exception {
        String template=new String(getClass().getResourceAsStream("/db/history/table.sql").readAllBytes(),StandardCharsets.UTF_8).trim();
        for(int database=0;database<2;database++) {
            StringBuilder expected=new StringBuilder("-- Fixed history topology v1. Initialize only a fresh query schema.\n");
            for(int table=database*16;table<(database+1)*16;table++)expected.append(template.replace("__TABLE__",String.format(Locale.ROOT,"ux_order_read_%02d",table))).append("\n");
            assertEquals(expected.toString().trim().replace("\r\n","\n"),Files.readString(Path.of("../infra/mysql/history"+database+".sql")).trim().replace("\r\n","\n"));
        }
        Map<?,?> overlay=new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(Path.of("../compose.history.yaml")));
        Map<?,?> services=(Map<?,?>)overlay.get("services");
        assertTrue(services.containsKey("history0"));assertTrue(services.containsKey("history1"));
        Map<?,?> backend=(Map<?,?>)services.get("backend");
        assertEquals("true",((Map<?,?>)((Map<?,?>)backend.get("build")).get("args")).get("WITH_SHARDING"));
    }
}
