package com.onnara.extract.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Address} 한국 주소 추출 휴리스틱 단위 테스트. */
class AddressTest {

    /** 도로명 주소(로/길+번호)를 문장 속에서도 추출하는지 검증한다. */
    @Test
    void extractsRoadAddresses() {
        assertEquals("군산시 비응로 107",
                Address.extract("군산시 비응로 107").orElseThrow());
        assertEquals("인천광역시 미추홀구 한나루로 607",
                Address.extract("주소는 인천광역시 미추홀구 한나루로 607 입니다").orElseThrow());
    }

    /** 지번 주소(동/리+번지)를 추출하는지 검증한다. */
    @Test
    void extractsLotAddresses() {
        assertTrue(Address.extract("인천광역시 중구 중산동 1849-3 인근 공유수면")
                .orElseThrow().startsWith("인천광역시 중구 중산동 1849-3"));
    }

    /** 주소가 아닌 텍스트·빈값·null은 empty로 처리되는지 검증한다. */
    @Test
    void nonAddressIsEmpty() {
        assertTrue(Address.extract("한국해양소년단 전북연맹").isEmpty());
        assertTrue(Address.extract("").isEmpty());
        assertTrue(Address.extract(null).isEmpty());
    }
}
