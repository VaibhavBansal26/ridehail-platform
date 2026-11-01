package com.ridehail.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehail.geo.GeoProvider.Point;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePointsTest {

    private static List<Point> wiggle(int n) {
        var pts = new ArrayList<Point>();
        for (int i = 0; i < n; i++) pts.add(new Point(12.9 + i * 0.0001, 77.5 + Math.sin(i / 7.0) * 0.001 + i * 0.0001));
        return pts;
    }

    @Test
    void aRouteThatIsShortEnoughIsLeftAlone() {
        var pts = wiggle(50);
        assertThat(RoutePoints.thin(pts, 250)).isEqualTo(pts);
    }

    /** A road that bends smoothly all the way, like a real one: the more points allowed, the closer it follows. */
    private static List<Point> arc(int n) {
        var pts = new ArrayList<Point>();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 1.5 * i / (n - 1);
            pts.add(new Point(12.9 + Math.sin(a) * 0.02, 77.5 + (1 - Math.cos(a)) * 0.02));
        }
        return pts;
    }

    @Test
    void aLongRouteIsThinnedToTheLimitAndKeepsBothEnds() {
        var pts = arc(5000);
        var thin = RoutePoints.thin(pts, 120);
        assertThat(thin.size()).isLessThanOrEqualTo(120).isGreaterThan(60); // as much of the shape as the limit allows, not a handful of points
        assertThat(thin.get(0)).isEqualTo(pts.get(0));
        assertThat(thin.get(thin.size() - 1)).isEqualTo(pts.get(pts.size() - 1));
    }

    @Test
    void theShapeSurvives_aCornerIsKeptAndAStraightRunIsDropped() {
        var pts = new ArrayList<Point>();
        for (int i = 0; i <= 100; i++) pts.add(new Point(12.9, 77.5 + i * 0.0001));          // 100 points east
        for (int i = 1; i <= 100; i++) pts.add(new Point(12.9 + i * 0.0001, 77.51));          // then 100 points north
        var thin = RoutePoints.thin(pts, 5);
        assertThat(thin).contains(new Point(12.9, 77.51)); // the corner
        assertThat(thin.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void aHugeRouteDoesNotOverflowTheStack() {
        assertThat(RoutePoints.thin(arc(50_000), 250).size()).isLessThanOrEqualTo(250);
    }

    @Test
    void aLimitBelowTwoIsRefused() {
        assertThatThrownBy(() -> RoutePoints.thin(wiggle(10), 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
