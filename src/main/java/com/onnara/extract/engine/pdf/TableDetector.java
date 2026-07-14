package com.onnara.extract.engine.pdf;

import com.onnara.extract.common.Tables;
import com.onnara.extract.common.model.RawTable;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 선분 클러스터링 표 탐지 — Python pdf/pdfplumber/reader.py의 격자 탐지 로직 포팅.
 *
 * <p>고시문 PDF의 표는 행 구분선이 짧은 세그먼트 여러 개로 쪼개져 그려져 있고
 * 페이지에 미세한 노이즈 선분이 수천 개 섞여 있어, 단순 표 탐지가 행을 통째로
 * 병합해 버린다. 그래서 콘텐츠 스트림의 선분(스트로크 선 + 사각형 테두리)을
 * 좌표별로 클러스터링해 총 커버리지가 충분한 좌표만 격자선으로 삼고,
 * 그 격자(explicit)로 셀 텍스트를 추출한다.
 */
public final class TableDetector {

    /** 좌표별 총 선분 길이가 이 값(pt) 이상이면 표 격자선으로 인정. */
    private static final double MIN_H_COVERAGE = 100.0;
    private static final double MIN_V_COVERAGE = 40.0;
    /** 이 간격(pt) 이내의 격자선 후보는 같은 선으로 병합. */
    private static final double COORD_TOL = 3.0;
    /** 표 몸통(수직선 y-범위) 밖의 수평선(제목 밑줄 등)을 배제할 때의 여유. */
    private static final double BAND_TOL = 5.0;
    /**
     * 선분을 수평/수직으로 분류할 때 허용하는 축 오차(pt).
     *
     * <p>고시문의 행 구분선은 1pt 미만의 점선 조각 수백 개로 그려지기도 하므로
     * (pdfplumber와 동일하게) 길이와 무관하게 좌표 동일 여부로만 분류해야 한다.
     */
    private static final double AXIS_EPS = 0.05;

    private TableDetector() {
    }

    /** 페이지에서 탐지된 표 (표 + y-범위). */
    public static final class DetectedTable {
        private final RawTable table;
        private final double top;
        private final double bottom;

        DetectedTable(RawTable table, double top, double bottom) {
            this.table = table;
            this.top = top;
            this.bottom = bottom;
        }

        public RawTable getTable() {
            return table;
        }

        /** 표 상단 y (top-origin, pt). */
        public double getTop() {
            return top;
        }

        /** 표 하단 y (top-origin, pt). */
        public double getBottom() {
            return bottom;
        }
    }

    /** 페이지의 표들을 탐지한다. 표 격자가 없으면 빈 목록. */
    public static List<DetectedTable> detect(PDPage page) throws IOException {
        EdgeCollector collector = new EdgeCollector(page);
        collector.processPage(page);

        GridCoords coords = gridCoords(collector);
        if (coords == null) {
            return Collections.emptyList();
        }

        List<List<String>> grid = extractGrid(page, coords.xs, coords.ys);
        grid = Tables.cleanGrid(grid);
        if (grid.isEmpty()) {
            return Collections.emptyList();
        }

        double top = coords.ys.get(0);
        double bottom = coords.ys.get(coords.ys.size() - 1);
        return Collections.singletonList(new DetectedTable(Tables.gridToTable(grid), top, bottom));
    }

    // ------------------------------------------------------------------
    // 격자 좌표 클러스터링 (_table_grid_coords 포팅)
    // ------------------------------------------------------------------

    private static final class GridCoords {
        final List<Double> xs;
        final List<Double> ys;

        GridCoords(List<Double> xs, List<Double> ys) {
            this.xs = xs;
            this.ys = ys;
        }
    }

    private static GridCoords gridCoords(EdgeCollector c) {
        // 수직 격자선 후보: 총 커버리지가 충분한 x 좌표
        List<Integer> strongV = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : c.vCov.entrySet()) {
            if (e.getValue() >= MIN_V_COVERAGE) {
                strongV.add(e.getKey());
            }
        }
        if (strongV.size() < 3) { // 2열 표라도 테두리 포함 수직선 3개
            return null;
        }

        // 수직선 3개 이상과 실제로 교차하는 수평선만 표 격자로 인정한다.
        // 제목 밑줄이나 페이지 여백선(좌우 세로줄과만 만나는 가로줄)을 배제.
        List<Integer> tableYs = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : c.hCov.entrySet()) {
            if (e.getValue() < MIN_H_COVERAGE) {
                continue;
            }
            int y = e.getKey();
            int crossing = 0;
            for (int x : strongV) {
                if (c.vTop.get(x) - BAND_TOL <= y && y <= c.vBottom.get(x) + BAND_TOL) {
                    crossing++;
                }
            }
            if (crossing >= 3) {
                tableYs.add(y);
            }
        }
        if (tableYs.size() < 2) {
            return null;
        }

        double bandTop = Collections.min(tableYs) - BAND_TOL;
        double bandBottom = Collections.max(tableYs) + BAND_TOL;
        List<Integer> tableXs = new ArrayList<>();
        for (int x : strongV) {
            if (c.vTop.get(x) <= bandBottom && c.vBottom.get(x) >= bandTop) {
                tableXs.add(x);
            }
        }

        return new GridCoords(mergeClose(tableXs), mergeClose(tableYs));
    }

    /** 가까운 좌표 후보들을 하나의 격자선으로 병합한다 (_merge_close 포팅). */
    static List<Double> mergeClose(List<Integer> coords) {
        List<Integer> sorted = new ArrayList<>(coords);
        Collections.sort(sorted);
        List<List<Integer>> groups = new ArrayList<>();
        for (int cVal : sorted) {
            if (!groups.isEmpty()) {
                List<Integer> last = groups.get(groups.size() - 1);
                if (cVal - last.get(last.size() - 1) <= COORD_TOL) {
                    last.add(cVal);
                    continue;
                }
            }
            List<Integer> g = new ArrayList<>();
            g.add(cVal);
            groups.add(g);
        }
        List<Double> merged = new ArrayList<>(groups.size());
        for (List<Integer> g : groups) {
            double sum = 0;
            for (int v : g) {
                sum += v;
            }
            merged.add(sum / g.size());
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // explicit 격자 → 셀 텍스트 추출 (pdfplumber explicit 전략 대체)
    // ------------------------------------------------------------------

    private static List<List<String>> extractGrid(PDPage page, List<Double> xs, List<Double> ys)
            throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.setSortByPosition(true);
        int nRows = ys.size() - 1;
        int nCols = xs.size() - 1;
        for (int i = 0; i < nRows; i++) {
            for (int j = 0; j < nCols; j++) {
                Rectangle2D.Double rect = new Rectangle2D.Double(
                        xs.get(j), ys.get(i),
                        xs.get(j + 1) - xs.get(j), ys.get(i + 1) - ys.get(i));
                stripper.addRegion(i + "_" + j, rect);
            }
        }
        stripper.extractRegions(page);

        List<List<String>> grid = new ArrayList<>(nRows);
        for (int i = 0; i < nRows; i++) {
            List<String> row = new ArrayList<>(nCols);
            for (int j = 0; j < nCols; j++) {
                String text = stripper.getTextForRegion(i + "_" + j);
                if (text == null) {
                    text = "";
                }
                row.add(text.replace("\r\n", "\n").replace('\r', '\n').trim());
            }
            grid.add(row);
        }
        return grid;
    }

    // ------------------------------------------------------------------
    // 콘텐츠 스트림 선분 수집 (pdfplumber page.edges 대체)
    // ------------------------------------------------------------------

    /**
     * 콘텐츠 스트림의 스트로크 선분과 사각형 테두리(표선이 얇은 채움 사각형으로
     * 그려지는 경우 포함)를 수평/수직 edge로 분해해 좌표별 커버리지를 집계한다.
     *
     * <p>좌표는 top-origin(pt)으로 변환하고 정수로 반올림해 집계한다
     * (Python 버전의 round(e["top"]) / round(e["x0"])와 동일).
     */
    private static final class EdgeCollector extends PDFGraphicsStreamEngine {

        final Map<Integer, Double> hCov = new TreeMap<>(); // 수평선: top(y) -> 총 길이
        final Map<Integer, Double> vCov = new TreeMap<>(); // 수직선: x -> 총 길이
        final Map<Integer, Double> vTop = new HashMap<>(); // 수직선: x -> 최소 top
        final Map<Integer, Double> vBottom = new HashMap<>(); // 수직선: x -> 최대 bottom

        private final double pageHeight;
        private final List<double[]> pathSegments = new ArrayList<>(); // {x0,y0,x1,y1} 장치 좌표
        private Point2D.Double currentPoint;
        private Point2D.Double subpathStart;

        EdgeCollector(PDPage page) {
            super(page);
            this.pageHeight = page.getMediaBox().getHeight();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            // 사각형 테두리를 4개 선분으로 분해 (pdfplumber의 rect -> edges와 동일)
            addSegment(p0, p1);
            addSegment(p1, p2);
            addSegment(p2, p3);
            addSegment(p3, p0);
            currentPoint = new Point2D.Double(p0.getX(), p0.getY());
            subpathStart = currentPoint;
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Double(x, y);
            subpathStart = currentPoint;
        }

        @Override
        public void lineTo(float x, float y) {
            Point2D.Double next = new Point2D.Double(x, y);
            if (currentPoint != null) {
                addSegment(currentPoint, next);
            }
            currentPoint = next;
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            // 곡선은 표 격자선이 아니므로 무시하고 현재 점만 갱신
            currentPoint = new Point2D.Double(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
            if (currentPoint != null && subpathStart != null) {
                addSegment(currentPoint, subpathStart);
                currentPoint = subpathStart;
            }
        }

        @Override
        public void endPath() {
            // 페인트 없이 끝나는 경로(클리핑 등)는 버린다
            pathSegments.clear();
        }

        @Override
        public void strokePath() {
            commitSegments();
        }

        @Override
        public void fillPath(int windingRule) {
            commitSegments();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            commitSegments();
        }

        @Override
        public void clip(int windingRule) {
            // no-op
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // no-op
        }

        @Override
        public void shadingFill(COSName shadingName) {
            // no-op
        }

        private void addSegment(Point2D a, Point2D b) {
            pathSegments.add(new double[] {a.getX(), a.getY(), b.getX(), b.getY()});
        }

        /** 그리기가 확정된 경로의 축 정렬 선분을 edge로 집계한다. */
        private void commitSegments() {
            for (double[] s : pathSegments) {
                double dx = Math.abs(s[2] - s[0]);
                double dy = Math.abs(s[3] - s[1]);
                if (dx == 0 && dy == 0) {
                    continue;
                }
                if (dy <= AXIS_EPS && dx >= dy) {
                    // 수평 선분: top 좌표 기준 커버리지 누적
                    double top = pageHeight - (s[1] + s[3]) / 2;
                    int key = (int) Math.round(top);
                    hCov.merge(key, dx, Double::sum);
                } else if (dx <= AXIS_EPS && dy > AXIS_EPS) {
                    // 수직 선분: x 좌표 기준 커버리지·y-범위 누적
                    int key = (int) Math.round((s[0] + s[2]) / 2);
                    double segTop = pageHeight - Math.max(s[1], s[3]);
                    double segBottom = pageHeight - Math.min(s[1], s[3]);
                    vCov.merge(key, dy, Double::sum);
                    vTop.merge(key, segTop, Math::min);
                    vBottom.merge(key, segBottom, Math::max);
                }
            }
            pathSegments.clear();
        }
    }
}