package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressTest {

    @Test
    void extractsRoadAddresses() {
        assertEquals("군산시 비응로 107",
                Address.extract("군산시 비응로 107").orElseThrow());
        assertEquals("인천광역시 미추홀구 한나루로 607",
                Address.extract("주소는 인천광역시 미추홀구 한나루로 607 입니다").orElseThrow());
    }

    @Test
    void extractsLotAddresses() {
        assertTrue(Address.extract("인천광역시 중구 중산동 1849-3 인근 공유수면")
                .orElseThrow().startsWith("인천광역시 중구 중산동 1849-3"));
    }

    @Test
    void nonAddressIsEmpty() {
        assertTrue(Address.extract("한국해양소년단 전북연맹").isEmpty());
        assertTrue(Address.extract("").isEmpty());
        assertTrue(Address.extract(null).isEmpty());
    }
}
