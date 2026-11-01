package com.ridehail.geo;

import com.ridehail.geo.GeoProvider.Point;
import java.util.ArrayList;
import java.util.List;

/** Thins a route to at most N points with the Ramer-Douglas-Peucker algorithm, keeping its shape and both ends. */
final class RoutePoints {
    private RoutePoints() {}

    static List<Point> thin(List<Point> points, int max) {
        if (max < 2) throw new IllegalArgumentException("max must be at least 2");
        if (points.size() <= max) return List.copyOf(points);
        // the finest tolerance (in degrees) that still fits: grow it until the route is small enough, then bisect between the last two
        // tolerances, so a long route keeps as much of its shape as the limit allows instead of collapsing to a few points
        double tooFine = 0, fits = 1e-6;
        List<Point> best = simplify(points, fits);
        while (best.size() > max) {
            tooFine = fits;
            fits *= 2;
            best = simplify(points, fits);
        }
        for (int i = 0; i < 16 && tooFine > 0; i++) {
            double mid = (tooFine + fits) / 2;
            List<Point> candidate = simplify(points, mid);
            if (candidate.size() > max) tooFine = mid;
            else { fits = mid; best = candidate; }
        }
        return best;
    }

    private static List<Point> simplify(List<Point> pts, double epsilon) {
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        // iterative, so a route of tens of thousands of points cannot overflow the stack
        var stack = new ArrayList<int[]>();
        stack.add(new int[] {0, pts.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.remove(stack.size() - 1);
            int from = range[0], to = range[1];
            double worst = 0;
            int at = -1;
            for (int i = from + 1; i < to; i++) {
                double d = distance(pts.get(i), pts.get(from), pts.get(to));
                if (d > worst) { worst = d; at = i; }
            }
            if (at >= 0 && worst > epsilon) {
                keep[at] = true;
                stack.add(new int[] {from, at});
                stack.add(new int[] {at, to});
            }
        }
        var out = new ArrayList<Point>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    /** Distance, in degrees, from p to the segment a-b (flat approximation: good over a city). */
    private static double distance(Point p, Point a, Point b) {
        double dx = b.lon() - a.lon(), dy = b.lat() - a.lat();
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.lon() - a.lon()) * dx + (p.lat() - a.lat()) * dy) / len2));
        double ex = a.lon() + t * dx - p.lon(), ey = a.lat() + t * dy - p.lat();
        return Math.sqrt(ex * ex + ey * ey);
    }
}
