package com.pbtservers.sharedb;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlValidationTest {
    @Test void escapesValidatedIdentifiers() { assertEquals("`luckperms_2`",DatabaseManager.identifier("luckperms_2")); }
    @Test void rejectsSqlInjectionAndOversizedIdentifiers() {
        for (String s:new String[]{"a`; DROP DATABASE mysql;--","a'b","a\\b","a\nb","","a".repeat(65),"mysql.user","-option"})
            assertThrows(IllegalArgumentException.class,()->DatabaseManager.identifier(s),s);
    }
    @Test void rejectsBroadOrAmbiguousHosts() {
        for (String s:new String[]{"%","172.18.%","localhost","127.0.0.1' OR 1=1","256.1.2.3","01.2.3.4","0.0.0.0","255.255.255.255"})
            assertThrows(IllegalArgumentException.class,()->DatabaseManager.authorizedHost(s),s);
        assertDoesNotThrow(()->DatabaseManager.authorizedHost("172.18.0.12"));
    }
}
