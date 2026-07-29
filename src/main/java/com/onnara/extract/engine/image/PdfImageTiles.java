package com.onnara.extract.engine.image;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 지면에 나란히 배치된 이미지 타일을 원래의 한 장으로 되돌린다.
 *
 * <p>PDF 생성기(한컴 PDF 출력 등)는 큰 래스터를 가로 띠 여러 개로 쪼개 각각 별도
 * XObject로 넣는 경우가 있다. 리소스만 훑으면 위성지도 한 장이 띠 N장으로 저장된다.
 * 배치 좌표를 보고 <b>맞닿아 있고 변이 일치하는</b> 타일을 도로 붙인다.
 *
 * <p>서로 다른 사진을 나란히 놓은 문서를 잘못 합치지 않도록 가드를 여러 겹 둔다
 * (픽셀 밀도·비트수·컬러스페이스 일치, 결과가 정확한 직사각형, 회전·기울임 제외).
 * 그래도 휴리스틱이므로 병합이 일어나면 호출부가 로그를 남긴다.
 */
public final class PdfImageTiles {

    /** 병합 결과 픽셀 면적 상한 — 초과하면 병합을 포기한다(메모리 보호). */
    private static final long MAX_MERGED_PIXELS = 80_000_000L;

    /** 픽셀 밀도가 이 비율 이상 어긋나면 다른 출처로 보고 병합하지 않는다. */
    private static final double MAX_DENSITY_MISMATCH = 0.02;

    /** 인접·일치 판정의 최소 허용 오차(pt). 변 길이의 1%와 비교해 큰 쪽을 쓴다. */
    private static final float MIN_EPSILON = 0.75f;

    /** 인스턴스화 방지 — 정적 병합 함수만 제공하는 유틸리티 클래스. */
    private PdfImageTiles() {
    }

    /**
     * 페이지에 그려진 이미지 1건. 좌표는 PDF 사용자 공간(y가 위로 증가)이며
     * {@code (x, y)}는 배치 사각형의 좌하단이다.
     *
     * @param skewed 회전·기울임·뒤집힘이 있어 타일 병합 대상으로 삼을 수 없는 배치
     */
    public record Placement(PDImageXObject image, BufferedImage decoded,
                            float x, float y, float width, float height, boolean skewed) {
    }

    /**
     * 병합 결과 1건. {@link #tiles}가 1장이면 병합이 일어나지 않은 것이며,
     * 그 경우 {@link #stitched()}는 원본 {@code decoded}와 같다.
     */
    public record Merged(List<Placement> tiles, BufferedImage stitched,
                         float x, float y, float width, float height) {

        /** 병합이 실제로 일어났는지 여부(타일 2장 이상). */
        public boolean isStitched() {
            return tiles.size() > 1;
        }

        /** 병합 전 단일 배치일 때 그 배치를 반환한다. */
        public Placement single() {
            return tiles.get(0);
        }

        /** 지면상 폭(mm). */
        public double widthMm() {
            return width * 25.4 / 72.0;
        }

        /** 지면상 높이(mm). */
        public double heightMm() {
            return height * 25.4 / 72.0;
        }
    }

    /**
     * 인접 타일을 수렴할 때까지 반복 병합한다.
     * 반환 순서는 입력의 첫 등장 순서를 따른다(파일명 번호를 안정적으로 매기기 위해).
     */
    public static List<Merged> merge(List<Placement> placements) {
        List<Group> groups = new ArrayList<>();
        for (Placement p : placements) {
            groups.add(Group.of(p));
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < groups.size(); i++) {
                for (int j = i + 1; j < groups.size(); j++) {
                    if (canMerge(groups.get(i), groups.get(j))) {
                        groups.set(i, Group.combine(groups.get(i), groups.get(j)));
                        groups.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }

        List<Merged> out = new ArrayList<>(groups.size());
        for (Group g : groups) {
            BufferedImage image = g.tiles.size() == 1 ? g.tiles.get(0).decoded() : stitch(g);
            out.add(new Merged(List.copyOf(g.tiles), image, g.x, g.y, g.width, g.height));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 그룹 · 병합 판정
    // ------------------------------------------------------------------

    /** 병합 중인 타일 묶음과 그 합집합 사각형. */
    private static final class Group {
        final List<Placement> tiles;
        final float x;
        final float y;
        final float width;
        final float height;

        Group(List<Placement> tiles, float x, float y, float width, float height) {
            this.tiles = tiles;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        static Group of(Placement p) {
            List<Placement> tiles = new ArrayList<>(1);
            tiles.add(p);
            return new Group(tiles, p.x(), p.y(), p.width(), p.height());
        }

        static Group combine(Group a, Group b) {
            List<Placement> tiles = new ArrayList<>(a.tiles.size() + b.tiles.size());
            tiles.addAll(a.tiles);
            tiles.addAll(b.tiles);
            float x = Math.min(a.x, b.x);
            float y = Math.min(a.y, b.y);
            float right = Math.max(a.x + a.width, b.x + b.width);
            float top = Math.max(a.y + a.height, b.y + b.height);
            return new Group(tiles, x, y, right - x, top - y);
        }

        /** 타일 픽셀 면적의 합 — 병합 결과 크기 가늠·상한 검사에 쓴다. */
        long pixelArea() {
            long area = 0;
            for (Placement p : tiles) {
                area += (long) p.decoded().getWidth() * p.decoded().getHeight();
            }
            return area;
        }
    }

    /** 두 그룹이 같은 원본에서 쪼개진 인접 타일인지 판정한다. */
    private static boolean canMerge(Group a, Group b) {
        for (Placement p : a.tiles) {
            if (p.skewed()) {
                return false;
            }
        }
        for (Placement p : b.tiles) {
            if (p.skewed()) {
                return false;
            }
        }
        if (a.pixelArea() + b.pixelArea() > MAX_MERGED_PIXELS) {
            return false;
        }
        if (!sameEncoding(a, b)) {
            return false;
        }
        if (!sameDensity(a, b)) {
            return false;
        }

        float eps = epsilon(a, b);
        boolean stacked = near(a.x, b.x, eps) && near(a.width, b.width, eps)
                && touchingVertically(a, b, eps);
        boolean sideBySide = near(a.y, b.y, eps) && near(a.height, b.height, eps)
                && touchingHorizontally(a, b, eps);
        if (!stacked && !sideBySide) {
            return false;
        }
        // 합집합이 정확한 직사각형일 때만 붙인다 — 계단 모양으로 자라는 것을 막는다.
        Group combined = Group.combine(a, b);
        double tileArea = 0;
        for (Placement p : combined.tiles) {
            tileArea += (double) p.width() * p.height();
        }
        double unionArea = (double) combined.width * combined.height;
        return Math.abs(unionArea - tileArea) <= eps * (combined.width + combined.height);
    }

    /** 인접·일치 판정 허용 오차: 관련 변 길이의 1%와 {@link #MIN_EPSILON} 중 큰 값. */
    private static float epsilon(Group a, Group b) {
        float smallest = Math.min(
                Math.min(a.width, a.height),
                Math.min(b.width, b.height));
        return Math.max(MIN_EPSILON, 0.01f * smallest);
    }

    /** 세로로 맞닿아 있는가(위아래 간격이 오차 이내). */
    private static boolean touchingVertically(Group a, Group b, float eps) {
        float gap = Math.max(a.y, b.y) - Math.min(a.y + a.height, b.y + b.height);
        return Math.abs(gap) <= eps;
    }

    /** 가로로 맞닿아 있는가(좌우 간격이 오차 이내). */
    private static boolean touchingHorizontally(Group a, Group b, float eps) {
        float gap = Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width);
        return Math.abs(gap) <= eps;
    }

    /** 두 값이 오차 이내로 같은가. */
    private static boolean near(float p, float q, float eps) {
        return Math.abs(p - q) <= eps;
    }

    /** 비트수·컬러스페이스가 모든 타일에서 같은가 — 다르면 다른 출처로 본다. */
    private static boolean sameEncoding(Group a, Group b) {
        PDImageXObject ref = a.tiles.get(0).image();
        for (Group g : List.of(a, b)) {
            for (Placement p : g.tiles) {
                if (p.image().getBitsPerComponent() != ref.getBitsPerComponent()) {
                    return false;
                }
                if (!colorSpaceName(p.image()).equals(colorSpaceName(ref))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 컬러스페이스 이름(없으면 빈 문자열) — 예외를 던지지 않는 안전한 조회. */
    private static String colorSpaceName(PDImageXObject image) {
        try {
            return image.getColorSpace() == null ? "" : image.getColorSpace().getName();
        } catch (Exception e) {
            return "";
        }
    }

    /** 픽셀 밀도(px/pt)가 두 그룹에서 일치하는가 — 다르면 별개의 사진이다. */
    private static boolean sameDensity(Group a, Group b) {
        double ax = densityX(a);
        double bx = densityX(b);
        double ay = densityY(a);
        double by = densityY(b);
        return withinTolerance(ax, bx) && withinTolerance(ay, by);
    }

    /** 두 밀도가 {@link #MAX_DENSITY_MISMATCH} 이내로 일치하는가. */
    private static boolean withinTolerance(double p, double q) {
        if (p <= 0 || q <= 0) {
            return false;
        }
        return Math.abs(p - q) / Math.max(p, q) <= MAX_DENSITY_MISMATCH;
    }

    /** 그룹의 가로 픽셀 밀도(px/pt) — 타일 중 최대값. */
    private static double densityX(Group g) {
        double d = 0;
        for (Placement p : g.tiles) {
            if (p.width() > 0) {
                d = Math.max(d, p.decoded().getWidth() / (double) p.width());
            }
        }
        return d;
    }

    /** 그룹의 세로 픽셀 밀도(px/pt) — 타일 중 최대값. */
    private static double densityY(Group g) {
        double d = 0;
        for (Placement p : g.tiles) {
            if (p.height() > 0) {
                d = Math.max(d, p.decoded().getHeight() / (double) p.height());
            }
        }
        return d;
    }

    // ------------------------------------------------------------------
    // 병합 실행
    // ------------------------------------------------------------------

    /**
     * 타일들을 배치 좌표에 맞춰 한 장으로 그린다.
     *
     * <p>PDF 사용자 공간은 y가 위로 증가하므로 그리는 좌표로 옮길 때 상하를 뒤집는다.
     * 배경은 흰색으로 채운다 — 지면 위에서 보이던 모습과 같게 만들기 위해서다.
     */
    private static BufferedImage stitch(Group g) {
        double dx = densityX(g);
        double dy = densityY(g);
        int width = Math.max(1, (int) Math.round(g.width * dx));
        int height = Math.max(1, (int) Math.round(g.height * dy));

        BufferedImage merged = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = merged.createGraphics();
        try {
            gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gfx.setColor(Color.WHITE);
            gfx.fillRect(0, 0, width, height);

            for (Placement p : g.tiles) {
                int left = (int) Math.round((p.x() - g.x) * dx);
                // y 뒤집기: 그룹 상단(g.y + g.height)이 그리는 좌표의 0이다
                int top = (int) Math.round((g.y + g.height - (p.y() + p.height())) * dy);
                int right = Math.min(width, left + (int) Math.round(p.width() * dx));
                int bottom = Math.min(height, top + (int) Math.round(p.height() * dy));
                left = Math.max(0, left);
                top = Math.max(0, top);
                if (right <= left || bottom <= top) {
                    continue;
                }
                BufferedImage tile = p.decoded();
                gfx.drawImage(tile, left, top, right, bottom,
                        0, 0, tile.getWidth(), tile.getHeight(), null);
            }
        } finally {
            gfx.dispose();
        }
        return merged;
    }
}
