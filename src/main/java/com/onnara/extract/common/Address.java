package com.onnara.extract.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국 주소 추출 휴리스틱.
 *
 * <p>시·도(선택) + 시·군·구 + 도로명(로/길+번호) 또는 지번(동/리/가+번지) 패턴을
 * 정규식으로 찾는다. 라벨 매핑으로 주소를 얻지 못한 문서에서
 * "허가를 받은 자" 등 값 블록으로부터 주소를 보충하는 용도.
 */
public final class Address {

    private static final String SIDO = "(?:[가-힣]{2,8}(?:특별시|광역시|특별자치시|특별자치도)|[가-힣]{2,8}도|서울|부산|대구|인천|광주|대전|울산|세종)";
    private static final String SIGUNGU = "[가-힣]{1,10}(?:시|군|구)";
    private static final String ROAD = "[가-힣A-Za-z0-9]+(?:로|길)\\s*\\d+(?:-\\d+)?(?:번길\\s*\\d+)?";
    private static final String LOT = "[가-힣0-9]+(?:동|리|가|읍|면)\\s*산?\\s*\\d+(?:-\\d+)?(?:번지)?(?:\\s*일원| 인근)?";

    private static final Pattern ADDRESS = Pattern.compile(
            "(?:" + SIDO + "\\s*)?" + SIGUNGU + "(?:\\s+[가-힣]{1,10}(?:읍|면|구))?"
                    + "\\s+(?:" + ROAD + "|" + LOT + ")"
                    + "(?:\\s*,?\\s*[가-힣0-9\\s]{0,20}(?:층|호|번지))?");

    private Address() {
    }

    /** 텍스트에서 첫 주소 후보를 추출한다. 없으면 empty. */
    public static Optional<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = ADDRESS.matcher(text);
        if (m.find()) {
            return Optional.of(m.group().trim());
        }
        return Optional.empty();
    }
}
