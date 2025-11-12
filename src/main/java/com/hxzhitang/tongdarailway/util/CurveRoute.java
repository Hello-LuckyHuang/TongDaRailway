package com.hxzhitang.tongdarailway.util;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 曲线类 根据曲线上的标架生成弯曲结构
public class CurveRoute {
    // 三维点类
    public static class Point3D {
        public double x, y, z;

        public Point3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Point3D(Vec3 p) {
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
        }

        public Point3D subtract(Point3D other) {
            return new Point3D(x - other.x, y - other.y, z - other.z);
        }

        public Point3D add(Point3D other) {
            return new Point3D(x + other.x, y + other.y, z + other.z);
        }

        public Point3D multiply(double scalar) {
            return new Point3D(x * scalar, y * scalar, z * scalar);
        }

        public double dot(Point3D other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public Point3D div(double n) {
            return new Point3D(x / n, y / n, z / n);
        }

        public Point3D cross(Point3D other) {
            return new Point3D(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x
            );
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public Point3D normalize() {
            double len = length();
            if (len == 0) return new Point3D(0, 0, 0);
            return new Point3D(x / len, y / len, z / len);
        }

        public double distanceTo(Point3D other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, z);
        }

        public ListTag toNBT() {
            ListTag list = new ListTag();
            list.add(DoubleTag.valueOf(x));
            list.add(DoubleTag.valueOf(y));
            list.add(DoubleTag.valueOf(z));
            return list;
        }

        public static Point3D fromNBT(ListTag list) {
            return new Point3D(
                    list.getDouble(0),
                    list.getDouble(1),
                    list.getDouble(2)
            );
        }
    }

    // 标架（坐标系）类
    public static class Frame {
        public Point3D position;    // 原点位置
        public Point3D tangent;     // 切线方向 (X轴)
        public Point3D normal;      // 法线方向 (Y轴)
        public Point3D binormal;    // 副法线方向 (Z轴)

        public Frame(Point3D position, Point3D tangent, Point3D normal, Point3D binormal) {
            this.position = position;
            this.tangent = tangent.normalize();
            this.normal = normal.normalize();
            this.binormal = binormal.normalize();
        }

        // 旋转标架到新的切线方向
        public Frame rotateToNewTangent(Point3D newTangent, Point3D newPosition) {
            Point3D oldT = this.tangent;
            Point3D newT = newTangent.normalize();

            // 计算旋转轴和角度
            Point3D rotationAxis = oldT.cross(newT);
            double axisLength = rotationAxis.length();

            if (axisLength < 1e-10) {
                // 切线方向变化很小，直接使用原标架
                return new Frame(newPosition, newT, this.normal, this.binormal);
            }

            rotationAxis = rotationAxis.normalize();
            double cosAngle = oldT.dot(newT);
            double angle = Math.acos(Math.max(-1, Math.min(1, cosAngle)));

            // 使用Rodrigues旋转公式旋转法线和副法线
            Point3D rotatedNormal = rotateVector(this.normal, rotationAxis, angle);
            Point3D rotatedBinormal = rotateVector(this.binormal, rotationAxis, angle);

            return new Frame(newPosition, newT, rotatedNormal, rotatedBinormal);
        }

        private Point3D rotateVector(Point3D vector, Point3D axis, double angle) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            // Rodrigues旋转公式: v_rot = v * cosθ + (axis × v) * sinθ + axis * (axis · v) * (1 - cosθ)
            Point3D term1 = vector.multiply(cos);
            Point3D term2 = axis.cross(vector).multiply(sin);
            Point3D term3 = axis.multiply(axis.dot(vector) * (1 - cos));

            return term1.add(term2).add(term3).normalize();
        }

        /**
         * 我们认为路面不可能出现左右倾斜，但是拿到的标架却可能有左右倾斜
         * 所以这里我们计算一个和切线垂直，以及切线和此向量张成的平面和xz平面垂直的
         * y坐标为正的法向量
         * @return 垂直法向量
         */
        public Point3D getVerticalXZNormal() {
            return new Point3D(-tangent.x*tangent.y, 1-Math.pow(tangent.y,2), -tangent.z*tangent.y).normalize();
        }

        @Override
        public String toString() {
            return String.format("pos: %s, tangent: %s, normal: %s, binormal: %s",
                    position, tangent, normal, binormal);
        }
    }

    // 曲线段接口
    public interface CurveSegment {
        Point3D evaluate(double t);
        Point3D derivative(double t);
        double getLength();
        List<Point3D> rasterize(int n); // 缩小n倍下栅格化
    }

    // 直线段实现
    public static class LineSegment implements CurveSegment {
        private Point3D start, end;

        public LineSegment(Point3D start, Point3D end) {
            this.start = start;
            this.end = end;
        }

        public LineSegment(Vec3 start, Vec3 end) {
            this.start = new Point3D(start);
            this.end = new Point3D(end);
        }

        @Override
        public Point3D evaluate(double t) {
            t = Math.max(0, Math.min(1, t));
            return new Point3D(
                    start.x + t * (end.x - start.x),
                    start.y + t * (end.y - start.y),
                    start.z + t * (end.z - start.z)
            );
        }

        @Override
        public Point3D derivative(double t) {
            return end.subtract(start).normalize();
        }

        @Override
        public double getLength() {
            return start.distanceTo(end);
        }

        @Override
        // 直线段的栅格化（Bresenham算法，只考虑x,z坐标）
        public List<Point3D> rasterize(int n) {
            List<Point3D> rasterPoints = new ArrayList<>();

            int x0 = (int) Math.round(start.x/n);
            int z0 = (int) Math.round(start.z/n);
            int x1 = (int) Math.round(end.x/n);
            int z1 = (int) Math.round(end.z/n);

            // 使用Bresenham直线算法
            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = x0 < x1 ? 1 : -1;
            int sz = z0 < z1 ? 1 : -1;
            int err = dx - dz;

            int x = x0;
            int z = z0;

            while (true) {
                rasterPoints.add(new Point3D(x, 0, z));

                if (x == x1 && z == z1) break;

                int e2 = 2 * err;
                if (e2 > -dz) {
                    err -= dz;
                    x += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    z += sz;
                }
            }

            return rasterPoints;
        }
    }

    // 三阶贝塞尔曲线实现
    public static class CubicBezier implements CurveSegment {
        private Point3D p0, p1, p2, p3;

        public CubicBezier(Point3D p0, Point3D p1, Point3D p2, Point3D p3) {
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
        }

        public static CubicBezier getCubicBezier(
                Vec3 startPos,           // 起点坐标
                Vec3 startAxis,          // 起点切线方向
                Vec3 endOffset,          // 终点相对于起点的偏移
                Vec3 endAxis             // 终点切线方向
        ) {
            // 计算终点的绝对坐标
            Vec3 endPos = startPos.add(endOffset);

            // 归一化轴向量
            Vec3 axis1 = startAxis.normalize();
            Vec3 axis2 = endAxis.normalize();

            // 计算控制手柄长度
            double handleLength = determineHandleLength(startPos, endPos, axis1, axis2);

            // 计算四个控制点
            Vec3 p0 = startPos;                                    // 起点
            Vec3 p1 = startPos.add(axis1.scale(handleLength));    // 第一个控制点
            Vec3 p2 = endPos.add(axis2.scale(handleLength));      // 第二个控制点
            Vec3 p3 = endPos; // 终点

            return new CubicBezier(new Point3D(p0), new Point3D(p1), new Point3D(p2), new Point3D(p3));
        }

        private static double determineHandleLength(Vec3 end1, Vec3 end2, Vec3 axis1, Vec3 axis2) {
            Vec3 cross1 = axis1.cross(new Vec3(0, 1, 0));
            Vec3 cross2 = axis2.cross(new Vec3(0, 1, 0));

            // 计算两个轴向的夹角
            double a1 = Mth.atan2(-axis2.z, -axis2.x);
            double a2 = Mth.atan2(axis1.z, axis1.x);
            double angle = a1 - a2;

            float circle = 2 * Mth.PI;
            angle = (angle + circle) % circle;
            if (Math.abs(circle - angle) < Math.abs(angle))
                angle = circle - angle;

            // 如果两个轴向平行
            if (Mth.equal(angle, 0)) {
                double[] intersect = VecHelper.intersect(end1, end2, axis1, cross2, Direction.Axis.Y);
                if (intersect != null) {
                    double t = Math.abs(intersect[0]);
                    double u = Math.abs(intersect[1]);
                    double min = Math.min(t, u);
                    double max = Math.max(t, u);

                    if (min > 1.2 && max / min > 1 && max / min < 3) {
                        return (max - min);
                    }
                }

                return end2.distanceTo(end1) / 3;
            }

            // 如果两个轴向不平行,使用圆弧公式计算
            double n = circle / angle;
            double factor = 4 / 3d * Math.tan(Math.PI / (2 * n));
            double[] intersect = VecHelper.intersect(end1, end2, cross1, cross2, Direction.Axis.Y);

            if (intersect == null) {
                return end2.distanceTo(end1) / 3;
            }

            double radius = Math.abs(intersect[1]);
            double handleLength = radius * factor;
            if (Mth.equal(handleLength, 0))
                handleLength = 1;

            return handleLength;
        }

        @Override
        public Point3D evaluate(double t) {
            t = Math.max(0, Math.min(1, t));
            double u = 1 - t;
            double u2 = u * u;
            double u3 = u2 * u;
            double t2 = t * t;
            double t3 = t2 * t;

            Point3D result = p0.multiply(u3)
                    .add(p1.multiply(3 * u2 * t))
                    .add(p2.multiply(3 * u * t2))
                    .add(p3.multiply(t3));

            return result;
        }

        @Override
        public Point3D derivative(double t) {
            t = Math.max(0, Math.min(1, t));
            double u = 1 - t;

            Point3D term1 = p1.subtract(p0).multiply(3 * u * u);
            Point3D term2 = p2.subtract(p1).multiply(6 * u * t);
            Point3D term3 = p3.subtract(p2).multiply(3 * t * t);

            return term1.add(term2).add(term3).normalize();
        }

        @Override
        public double getLength() {
            int steps = 100;
            double length = 0;
            Point3D prev = evaluate(0);

            for (int i = 1; i <= steps; i++) {
                Point3D current = evaluate(i / (double) steps);
                length += prev.distanceTo(current);
                prev = current;
            }

            return length;
        }

        // 栅格化三阶贝塞尔曲线，返回经过的所有栅格点
        @Override
        public List<Point3D> rasterize(int n) {
            Set<Point3D> rasterPoints = new HashSet<>();

            // 使用递归细分算法进行栅格化
            recursiveRasterize(p0.div(n), p1.div(n), p2.div(n), p3.div(n), rasterPoints, 0);

            return new ArrayList<>(rasterPoints);
        }

        // 递归细分栅格化方法
        private void recursiveRasterize(Point3D p0, Point3D p1, Point3D p2, Point3D p3,
                                               Set<Point3D> rasterPoints, int depth) {
            // 最大递归深度，控制精度
            final int MAX_DEPTH = 8;

            // 计算四个控制点的整数栅格坐标（只考虑x,z）
            int x0 = (int) Math.round(p0.x);
            int z0 = (int) Math.round(p0.z);
            int x1 = (int) Math.round(p1.x);
            int z1 = (int) Math.round(p1.z);
            int x2 = (int) Math.round(p2.x);
            int z2 = (int) Math.round(p2.z);
            int x3 = (int) Math.round(p3.x);
            int z3 = (int) Math.round(p3.z);

            // 如果所有控制点都在同一个栅格内，或者达到最大深度，则添加端点并返回
            if ((x0 == x1 && x1 == x2 && x2 == x3 && z0 == z1 && z1 == z2 && z2 == z3) || depth >= MAX_DEPTH) {
                rasterPoints.add(new Point3D(x0, 0, z0));
                rasterPoints.add(new Point3D(x3, 0, z3));
                return;
            }

            // 计算中点，将贝塞尔曲线分成两段
            Point3D[] subdivided = subdivideBezier(p0, p1, p2, p3, 0.5);
            Point3D leftP0 = subdivided[0];
            Point3D leftP1 = subdivided[1];
            Point3D leftP2 = subdivided[2];
            Point3D leftP3 = subdivided[3];
            Point3D rightP0 = subdivided[3];  // 注意：左段的终点就是右段的起点
            Point3D rightP1 = subdivided[4];
            Point3D rightP2 = subdivided[5];
            Point3D rightP3 = subdivided[6];

            // 递归处理左右两段
            recursiveRasterize(leftP0, leftP1, leftP2, leftP3, rasterPoints, depth + 1);
            recursiveRasterize(rightP0, rightP1, rightP2, rightP3, rasterPoints, depth + 1);
        }

        // 贝塞尔曲线细分算法：将曲线在参数t处分成两段
        private Point3D[] subdivideBezier(Point3D p0, Point3D p1, Point3D p2, Point3D p3, double t) {
            // 计算第一组中间点
            Point3D p01 = interpolate(p0, p1, t);
            Point3D p12 = interpolate(p1, p2, t);
            Point3D p23 = interpolate(p2, p3, t);

            // 计算第二组中间点
            Point3D p012 = interpolate(p01, p12, t);
            Point3D p123 = interpolate(p12, p23, t);

            // 计算分割点
            Point3D p0123 = interpolate(p012, p123, t);

            // 返回两段曲线的控制点
            // 左段: p0, p01, p012, p0123
            // 右段: p0123, p123, p23, p3
            return new Point3D[] {
                    p0, p01, p012, p0123,
                    p123, p23, p3
            };
        }

        // 线性插值
        private Point3D interpolate(Point3D a, Point3D b, double t) {
            return new Point3D(
                    a.x + t * (b.x - a.x),
                    a.y + t * (b.y - a.y),  // 虽然y坐标不考虑，但为了完整性保留
                    a.z + t * (b.z - a.z)
            );
        }
    }

    // 最近点查找结果
    public static class NearestPointResult {
        public Point3D nearestPoint;
        public double parameter;
        public double distance;
        public Frame frame;
        public int segmentIndex;

        public NearestPointResult(Point3D nearestPoint, double parameter, double distance,
                                  Frame frame, int segmentIndex) {
            this.nearestPoint = nearestPoint;
            this.parameter = parameter;
            this.distance = distance;
            this.frame = frame;
            this.segmentIndex = segmentIndex;
        }
    }

    // 平行传输标架计算器
    public static class ParallelTransportFrameCalculator {
        // 为整个曲线计算平行传输标架
        public static List<Frame> computeParallelTransportFrames(CompositeCurve curve, int sampleCount) {
            List<Frame> frames = new ArrayList<>();

            if (curve.getSegments().isEmpty()) {
                return frames;
            }

            // 采样曲线点
            List<Point3D> samples = new ArrayList<>();
            List<Point3D> tangents = new ArrayList<>();

            for (int i = 0; i < sampleCount; i++) {
                double t = i / (double) (sampleCount - 1);
                CurveSegment segment = curve.getSegmentAtParameter(t);
                double segmentT = curve.getSegmentParameter(t);

                Point3D point = segment.evaluate(segmentT);
                Point3D tangent = segment.derivative(segmentT);

                samples.add(point);
                tangents.add(tangent);
            }

            // 计算初始标架
            Frame initialFrame = computeInitialFrame(samples.get(0), tangents.get(0));
            frames.add(initialFrame);

            // 平行传输后续标架
            for (int i = 1; i < samples.size(); i++) {
                Frame prevFrame = frames.get(i - 1);
                Point3D currentPoint = samples.get(i);
                Point3D currentTangent = tangents.get(i);

                Frame newFrame = prevFrame.rotateToNewTangent(currentTangent, currentPoint);
                frames.add(newFrame);
            }

            return frames;
        }

        // 在任意参数位置通过插值获取标架
        public static Frame getFrameAtParameter(List<Frame> frames, double t, int sampleCount) {
            if (frames.isEmpty()) {
                return null;
            }

            double index = t * (sampleCount - 1);
            int idx1 = (int) Math.floor(index);
            int idx2 = (int) Math.ceil(index);

            if (idx1 < 0) idx1 = 0;
            if (idx2 >= frames.size()) idx2 = frames.size() - 1;
            if (idx1 == idx2) return frames.get(idx1);

            double blend = index - idx1;
            return interpolateFrames(frames.get(idx1), frames.get(idx2), blend);
        }

        // 插值两个标架
        private static Frame interpolateFrames(Frame frame1, Frame frame2, double blend) {
            Point3D pos = interpolatePoints(frame1.position, frame2.position, blend);
            Point3D tangent = interpolatePoints(frame1.tangent, frame2.tangent, blend).normalize();

            // 对法线和副法线进行SLERP插值
            Point3D normal = slerp(frame1.normal, frame2.normal, blend);
            Point3D binormal = tangent.cross(normal).normalize();
            normal = binormal.cross(tangent).normalize();

            return new Frame(pos, tangent, normal, binormal);
        }

        private static Point3D interpolatePoints(Point3D p1, Point3D p2, double blend) {
            return new Point3D(
                    p1.x + blend * (p2.x - p1.x),
                    p1.y + blend * (p2.y - p1.y),
                    p1.z + blend * (p2.z - p1.z)
            );
        }

        private static Point3D slerp(Point3D v1, Point3D v2, double blend) {
            double dot = Math.max(-1, Math.min(1, v1.dot(v2)));
            double angle = Math.acos(dot);

            if (angle < 1e-10) {
                return interpolatePoints(v1, v2, blend).normalize();
            }

            double sinAngle = Math.sin(angle);
            double w1 = Math.sin((1 - blend) * angle) / sinAngle;
            double w2 = Math.sin(blend * angle) / sinAngle;

            return new Point3D(
                    w1 * v1.x + w2 * v2.x,
                    w1 * v1.y + w2 * v2.y,
                    w1 * v1.z + w2 * v2.z
            ).normalize();
        }

        private static Frame computeInitialFrame(Point3D point, Point3D tangent) {
            Point3D t = tangent.normalize();
            Point3D n, b;

            // 选择与切线不共线的参考向量
            if (Math.abs(t.x) > 0.1 || Math.abs(t.y) > 0.1) {
                n = new Point3D(0, 0, 1).cross(t).normalize();
            } else {
                n = new Point3D(1, 0, 0).cross(t).normalize();
            }

            b = t.cross(n).normalize();
            n = b.cross(t).normalize();

            return new Frame(point, t, n, b);
        }
    }

    // 复合曲线类
    public static class CompositeCurve {
        private List<CurveSegment> segments = new ArrayList<>();
        private List<Double> segmentLengths = new ArrayList<>();

        public double getTotalLength() {
            return totalLength;
        }

        private double totalLength = 0;
        private List<Frame> parallelFrames;
        private boolean framesComputed = false;
        private static final int SAMPLE_COUNT = 100;

        public void addSegment(CurveSegment segment) {
            segments.add(segment);
            double length = segment.getLength();
            segmentLengths.add(length);
            totalLength += length;
            framesComputed = false; // 标记需要重新计算标架
        }

        public List<CurveSegment> getSegments() {
            return segments;
        }

        public CurveSegment getSegmentAtParameter(double t) {
            t = Math.max(0, Math.min(1, t));
            double targetLength = t * totalLength;
            double accumulated = 0;

            for (int i = 0; i < segments.size(); i++) {
                double segmentLength = segmentLengths.get(i);
                if (accumulated + segmentLength >= targetLength) {
                    return segments.get(i);
                }
                accumulated += segmentLength;
            }

            return segments.get(segments.size() - 1);
        }

        public double getSegmentParameter(double t) {
            t = Math.max(0, Math.min(1, t));
            double targetLength = t * totalLength;
            double accumulated = 0;

            for (int i = 0; i < segments.size(); i++) {
                double segmentLength = segmentLengths.get(i);
                if (accumulated + segmentLength >= targetLength) {
                    double segmentT = (targetLength - accumulated) / segmentLength;
                    return Math.max(0, Math.min(1, segmentT));
                }
                accumulated += segmentLength;
            }

            return 1.0;
        }

        private void ensureFramesComputed() {
            if (!framesComputed || parallelFrames == null) {
                parallelFrames = ParallelTransportFrameCalculator.computeParallelTransportFrames(this, SAMPLE_COUNT);
                framesComputed = true;
            }
        }

        public NearestPointResult findNearestPoint(Point3D point) {
            if (segments.isEmpty()) {
                throw new IllegalStateException("曲线没有定义任何段");
            }

            ensureFramesComputed();

            NearestPointResult bestResult = null;

            for (int i = 0; i < segments.size(); i++) {
                CurveSegment segment = segments.get(i);
                NearestPointResult segmentResult = findNearestOnSegment(segment, point, i);

                if (bestResult == null || segmentResult.distance < bestResult.distance) {
                    bestResult = segmentResult;
                }
            }

            return bestResult;
        }

        private NearestPointResult findNearestOnSegment(CurveSegment segment, Point3D point, int segmentIndex) {
            int iterations = 20;
            double low = 0.0;
            double high = 1.0;

            for (int i = 0; i < iterations; i++) {
                double t1 = low + (high - low) / 3.0;
                double t2 = high - (high - low) / 3.0;

                Point3D p1 = segment.evaluate(t1);
                Point3D p2 = segment.evaluate(t2);

                double d1 = p1.distanceTo(point);
                double d2 = p2.distanceTo(point);

                if (d1 < d2) {
                    high = t2;
                } else {
                    low = t1;
                }
            }

            double bestT = (low + high) / 2.0;
            Point3D nearestPoint = segment.evaluate(bestT);
            double distance = nearestPoint.distanceTo(point);

            // 使用平行传输标架
            Frame frame = ParallelTransportFrameCalculator.getFrameAtParameter(
                    parallelFrames, getGlobalParameter(segmentIndex, bestT), SAMPLE_COUNT);

            return new NearestPointResult(nearestPoint, bestT, distance, frame, segmentIndex);
        }

        public double getGlobalParameter(int segmentIndex, double segmentT) {
            double accumulated = 0;
            for (int i = 0; i < segmentIndex; i++) {
                accumulated += segmentLengths.get(i);
            }
            accumulated += segmentLengths.get(segmentIndex) * segmentT;
            return accumulated / totalLength;
        }

        public ListTag toNBT() {
            ListTag curveTag = new ListTag();
            for (CurveSegment segment : segments) {
                ListTag parameters = new ListTag();
                if (segment instanceof LineSegment line) {
                    parameters.add(line.start.toNBT());
                    parameters.add(line.end.toNBT());
                } else if (segment instanceof CubicBezier bezier) {
                    parameters.add(bezier.p0.toNBT());
                    parameters.add(bezier.p1.toNBT());
                    parameters.add(bezier.p2.toNBT());
                    parameters.add(bezier.p3.toNBT());
                }
                curveTag.add(parameters);
            }
            return curveTag;
        }

        public static CompositeCurve fromNBT(ListTag curveTag) {
            CompositeCurve curve = new CompositeCurve();
            for (int i = 0; i < curveTag.size(); i++) {
                ListTag parameters = curveTag.getList(i);
                if (parameters.size() == 2) {
                    Point3D start = Point3D.fromNBT((ListTag) parameters.get(0));
                    Point3D end = Point3D.fromNBT((ListTag) parameters.get(1));
                    curve.addSegment(new LineSegment(start, end));
                } else if (parameters.size() == 4) {
                    Point3D p0 = Point3D.fromNBT((ListTag) parameters.get(0));
                    Point3D p1 = Point3D.fromNBT((ListTag) parameters.get(1));
                    Point3D p2 = Point3D.fromNBT((ListTag) parameters.get(2));
                    Point3D p3 = Point3D.fromNBT((ListTag) parameters.get(3));
                    curve.addSegment(new CubicBezier(p0, p1, p2, p3));
                }
            }
            return curve;
        }
    }

    /**
     * 调整平行传输标架为一个只保留切线平面方向的标架
     * 假设我们的路升降十分小幅度，所以不需要考虑法线方向
     * @param inputFrame 输入的标架
     * @return 调整后的标架
     */
    public static Frame adjustmentFrame(Frame inputFrame) {
        // 创建新的Frame对象，避免修改原始对象
        Frame outputFrame = new Frame(inputFrame.position, inputFrame.tangent, inputFrame.normal, inputFrame.binormal);
        // 取整标架y坐标放置某些时候标架上的y坐标抖动
        outputFrame.position = inputFrame.position;

        // 强制标架法线朝上
        var worldUp = new CurveRoute.Point3D(0, 1, 0);
        if (outputFrame.normal.dot(worldUp) < 0) {
            outputFrame = new CurveRoute.Frame(outputFrame.position, outputFrame.tangent,
                    outputFrame.normal.multiply(-1), outputFrame.binormal.multiply(-1));
        }

        // 切线方向仅水平
        outputFrame.tangent = new Point3D(inputFrame.tangent.x, 0, inputFrame.tangent.z).normalize();

        // 法线竖直向上
        outputFrame.normal = new Point3D(0, 1, 0);

        // 副法线为切线和法线的叉积
        outputFrame.binormal = outputFrame.tangent.cross(outputFrame.normal);

        return outputFrame;
    }

    /*
    // 使用示例
    public static void main(String[] args) {
        // 创建复合曲线
        CompositeCurve curve = new CompositeCurve();

        // 添加直线段
        Point3D lineStart = new Point3D(0, 320, 0);
        Point3D lineEnd = new Point3D(16*2, 320, 16*2);
        curve.addSegment(new LineSegment(lineStart, lineEnd));

        // 添加贝塞尔曲线段
        Point3D p0 = new Point3D(16*2, 320, 16*2);
        Point3D p1 = new Point3D(32*2, 320, 32*2);
        Point3D p2 = new Point3D(32*2, 320, 60*2);
        Point3D p3 = new Point3D(60*2, 320, 60*2);
        curve.addSegment(new CubicBezier(p0, p1, p2, p3));

        // 测试点
        Point3D testPoint = new Point3D(120, 220, 120);

        // 查找最近点
        NearestPointResult result = curve.findNearestPoint(testPoint);

        // 输出结果
        System.out.println("testPoint: " + testPoint);
        System.out.println("nearestPoint: " + result.nearestPoint);
        System.out.println("dis: " + result.distance);
        System.out.println("parameter t: " + result.parameter);
        System.out.println("segmentIndex: " + result.segmentIndex);
        System.out.println("Frame: " + result.frame);

        for (int i = 0; i < 10; i++) {
            Point3D testPoint0 = new Point3D(i, 220, i);
            // 查找最近点
            NearestPointResult result0 = curve.findNearestPoint(testPoint0);

            double t = result0.parameter;
            double t1 = curve.getGlobalParameter(result0.segmentIndex, t);
            double l = curve.getTotalLength();
            System.out.println(t1 + " " + l + " " + l * t1);
        }

        int[][] pic = new int[64][64];
        for (CurveSegment segment : curve.getSegments()) {
            for (Point3D p : segment.rasterize(2)) {
                pic[(int) p.x][(int) p.z] = 255;
            }
        }
//        ArrayToPNG.saveArrayAsPNG(pic, List.of(), "D://测试噪声图//噪声图//testb.png");

        // 显示标架的连续性检查
        System.out.println("\n标架连续性检查:");
        List<Frame> frames = curve.parallelFrames;
        for (int i = 1; i < Math.min(5, frames.size()); i++) {
            Frame f1 = frames.get(i-1);
            Frame f2 = frames.get(i);
            double dot = f1.normal.dot(f2.normal);
            System.out.printf("相邻标架法线点积: %.6f (接近1表示连续)\n", dot);
        }
    }*/
}
