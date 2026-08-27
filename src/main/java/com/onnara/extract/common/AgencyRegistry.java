package com.onnara.extract.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 입력 폴더 한 벌을 훑어 기관 목록과 파일별 수집처를 확정한다.
 *
 * <p><b>기관의 정의처는 크롤러 수집 결과 엑셀의 검증요약 시트다</b> — 거기 실린
 * {@code 번호}(1 · 12_1 · 72_2)와 {@code 기관명}(목포시청_지난자료)이 기관을 정한다.
 * 이 클래스가 읽는 폴더명은 크롤 산출물이 아닌 파일(수동 수집분, {@code samples/})에만 남는
 * <b>폴백</b>이다. 규약을 두 벌 유지하지 않아도 되는 이유는, 검증요약의 두 칸을 {@code _}로
 * 이으면({@code 72_1} + {@code 전자관보_농림축산식품부}) 폴더명과 글자 하나까지 같은 형식이
 * 되어 {@link SourceFolder}가 양쪽을 그대로 읽기 때문이다 — 08.07 산출물의 90행이 전부
 * 파싱되고 게시완료 16기관·전자관보 2기관도 정확히 갈렸다.
 *
 * <p>수집결과 시트의 지자체명이 곧 검증요약의 기관명이지만 <b>전자관보만은 예외</b>다 —
 * 게시물 쪽 지자체명이 "전자관보" 한 값이라 검증요약의 두 줄 중 어느 쪽인지 가릴 수 없다.
 * 그 행만 비고를 읽어 발령 기관을 얻는다({@link SourceFolder#gazetteAgencyOf}).
 *
 * <p>기관번호는 <b>행 하나만 봐서는 정할 수 없다.</b> 보통은 앞 번호가 곧 기관번호지만,
 * 한 번호 아래에 서로 다른 기관이 들어앉는 경우가 있다:
 * <pre>
 * 72_1_전자관보_농림축산식품부
 * 72_2_전자관보_새만금개발청
 * </pre>
 * 이때는 72를 아무도 쓰지 않고 최대번호 뒤로 새 번호를 발급한다(→ 73, 74). 이름이 같은
 * {@code 12_1_목포시청}·{@code 12_2_목포시청_지난자료}는 게시판만 둘인 한 기관이므로 12 하나다.
 *
 * <p>발급 순서가 {@code (폴더번호, 하위번호, 이름)} 오름차순으로 고정돼 있어, 같은 입력이면
 * 매 실행 같은 번호가 나온다 — 재적재가 멱등하려면 이 성질이 있어야 한다.
 *
 * <p>결과가 0건인 기관도 등록한다. 성공한 파일만 적재하면 "첨부 401건 중 추출 0건"인 기관이
 * DB에서 아예 보이지 않는다는 문제를, 폴더가 통째로 비었을 때도 똑같이 막는다. 이 성질이
 * {@code OS_CRWL_LOG_DTL.INSTT_SN}을 FK로 둘 수 있는 근거이기도 하다 — 수집에 실패해 게시물이
 * 하나도 없는 기관도 부모 행이 있으므로 그 실패 로그가 FK 위반으로 거부되지 않는다.
 */
public final class AgencyRegistry {

    /**
     * 기관 1행({@code agencies}).
     *
     * @param agncyNo 확정된 기관번호
     * @param agncyNm  기관명
     * @param kndCd mof / local / central / gazette
     */
    public record Agency(int agncyNo, String agncyNm, String kndCd) {
    }

    /**
     * 파일 한 건의 수집처 — 어느 기관의 어느 게시판에서 긁어 왔는가.
     *
     * @param agncyNo   기관번호({@code OS_NOTI_BAS.INSTT_SN})
     * @param agncyNm 기관명({@code OS_INSTT_BAS.INSTT_NM})
     * @param kndCd   기관 종류({@code OS_INSTT_BAS.INSTT_KND_CD})
     * @param boardCd  게시판 구분({@code OS_NOTI_BAS.BBS_STTS_CD})
     */
    public record SourceBoard(
            @JsonProperty("agncy_no") int agncyNo,
            @JsonProperty("agncy_nm") String agncyNm,
            @JsonProperty("knd_cd") String kndCd,
            @JsonProperty("board_cd") String boardCd) {
    }

    /** 폴더명 → 수집처. 파일의 첫 경로 조각으로 찾는다. */
    private final Map<String, SourceBoard> byFolder;

    /** 기관번호 오름차순 기관 목록. */
    private final List<Agency> agencies;

    /** 파일 경로를 폴더명으로 되돌릴 기준 경로(절대·정규화). */
    private final Path root;

    /** 확정된 표를 들고 만든다 — 생성은 {@link #scan}과 {@link #empty}만 한다. */
    private AgencyRegistry(Path root, Map<String, SourceBoard> byFolder, List<Agency> agencies) {
        this.root = root;
        this.byFolder = byFolder;
        this.agencies = agencies;
    }

    /** 기관을 하나도 모르는 레지스트리 — 폴더 규약이 없는 입력에 쓴다. */
    public static AgencyRegistry empty() {
        return new AgencyRegistry(null, Map.of(), List.of());
    }

    /**
     * 입력 루트 <b>바로 아래</b> 디렉터리들을 기관으로 등록한다.
     *
     * <p>더 깊은 곳은 보지 않는다 — 기관 폴더 안의 하위 폴더는 그 기관의 것이지 별개 기관이 아니다.
     *
     * @param inputRoot 배치 입력 폴더. 없거나 파일이면 빈 레지스트리를 돌려준다
     */
    public static AgencyRegistry scan(Path inputRoot) throws IOException {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            return empty();
        }
        Path root = inputRoot.toAbsolutePath().normalize();

        // 폴더번호로 묶는다. TreeMap이라 번호 오름차순 순회가 보장된다
        Map<Integer, List<Folder>> groups = new TreeMap<>();
        int maxFolderNo = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String folderName = child.getFileName().toString();
                Optional<SourceFolder.Parsed> parsed = SourceFolder.parse(folderName);
                if (parsed.isEmpty()) {
                    System.out.println("[경고] 폴더명이 기관 규약에 맞지 않아 기관을 붙이지 못합니다: "
                            + folderName);
                    continue;
                }
                SourceFolder.Parsed p = parsed.get();
                groups.computeIfAbsent(p.folderNo(), k -> new ArrayList<>())
                        .add(new Folder(folderName, p));
                maxFolderNo = Math.max(maxFolderNo, p.folderNo());
            }
        }

        Map<String, SourceBoard> byFolder = new LinkedHashMap<>();
        List<Agency> agencies = new ArrayList<>();
        int nextFreeNo = maxFolderNo + 1;

        for (List<Folder> group : groups.values()) {
            group.sort(Comparator.comparingInt((Folder f) -> f.parsed().subNo())
                    .thenComparing(Folder::folderName));
            // 같은 번호 아래 (이름, 종류)가 몇 가지인가 — 하나면 게시판만 여럿인 한 기관이다
            Map<String, List<Folder>> byIdentity = new LinkedHashMap<>();
            for (Folder folder : group) {
                byIdentity.computeIfAbsent(identityOf(folder.parsed()), k -> new ArrayList<>())
                        .add(folder);
            }
            boolean collides = byIdentity.size() > 1;
            for (List<Folder> sameAgency : byIdentity.values()) {
                SourceFolder.Parsed head = sameAgency.get(0).parsed();
                int agncyNo = collides ? nextFreeNo++ : head.folderNo();
                agencies.add(new Agency(agncyNo, head.agncyNm(), head.kndCd()));
                for (Folder folder : sameAgency) {
                    byFolder.put(folder.folderName(), new SourceBoard(agncyNo,
                            folder.parsed().agncyNm(), folder.parsed().kndCd(),
                            folder.parsed().boardCd()));
                }
            }
        }
        agencies.sort(Comparator.comparingInt(Agency::agncyNo));
        return new AgencyRegistry(root, byFolder, agencies);
    }

    /**
     * 파일이 어느 기관 게시판에서 왔는지 — 루트 기준 <b>첫 경로 조각</b>으로 찾는다.
     *
     * @return 루트 바로 밑에 놓인 파일이거나 규약에 맞지 않는 폴더면 {@link Optional#empty()}
     */
    public Optional<SourceBoard> boardOf(Path file) {
        if (root == null || file == null) {
            return Optional.empty();
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return Optional.empty();
        }
        Path relative = root.relativize(absolute);
        // 조각이 하나면 파일 자신이다 — 사이에 폴더가 없으니 기관을 알 수 없다
        if (relative.getNameCount() < 2) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFolder.get(relative.getName(0).toString()));
    }

    /** 등록된 기관 전부(기관번호 오름차순). 파일이 0건인 폴더도 들어 있다. */
    public List<Agency> agencies() {
        return agencies;
    }

    /** 기관 동일성 키 — 이름이 같아도 수집 경로가 다르면(전자관보) 다른 기관으로 본다. */
    private static String identityOf(SourceFolder.Parsed parsed) {
        return parsed.kndCd() + '\u0000' + parsed.agncyNm();
    }

    /** 스캔 도중의 폴더 한 건 — 원본 폴더명과 파싱 결과를 함께 들고 다닌다. */
    private record Folder(String folderName, SourceFolder.Parsed parsed) {
    }
}
