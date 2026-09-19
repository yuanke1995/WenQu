package com.wisesoft.wenqu.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * PurePosixPath 的最小移植（只含工程已用语义面）。
 *
 * <p>对应参考实现中的 {@code pathlib.PurePosixPath}：分隔符折叠、绝对路径判断、
 * parts（绝对路径首段为 "/"）、parents 链、{@code as_posix()} 的规范化字符串。
 * 不解析 {@code .}/​{@code ..}（PurePosixPath 同样保留它们作为组件）。
 */
public final class PosixPathLite {

    private static final PosixPathLite EMPTY = new PosixPathLite(false, List.of());

    private final boolean absolute;
    private final List<String> segments;
    private final String normalized;

    private PosixPathLite(boolean absolute, List<String> segments) {
        this.absolute = absolute;
        this.segments = Collections.unmodifiableList(segments);
        this.normalized = absolute ? "/" + String.join("/", segments) : String.join("/", segments);
    }

    /** pathlib.Path.suffix：最后一段的扩展名（含点，无扩展名/隐藏名为空）。 */
    public static String suffixOf(String value) {
        String last = value;
        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
        if (slash >= 0) {
            last = last.substring(slash + 1);
        }
        int dot = last.lastIndexOf('.');
        if (dot <= 0) {
            // ".bashrc" 之类的隐藏名没有扩展名
            return "";
        }
        return last.substring(dot);
    }

    /** pathlib.Path.name：最后一段文件名（含扩展名；为空返回原串）。 */
    public static String nameOf(String value) {
        if (value == null) {
            return "";
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    /** pathlib.Path.stem：最后一段文件名去掉扩展名（无扩展名/隐藏名返回整段）。 */
    public static String stemOf(String value) {
        String name = nameOf(value);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    public static PosixPathLite parse(String value) {
        String text = value == null ? "" : value;
        boolean absolute = text.startsWith("/");
        List<String> segments = new ArrayList<>();
        for (String part : text.split("/")) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return new PosixPathLite(absolute, segments);
    }

    public static PosixPathLite empty() {
        return EMPTY;
    }

    public boolean isAbsolute() {
        return absolute;
    }

    /** 组件数（绝对路径首段 "/" 计入，对应 parts 长度）。 */
    public int partCount() {
        return segments.size() + (absolute ? 1 : 0);
    }

    /** 第 index 个组件（对应 parts[index]；绝对路径 index=0 为 "/"）。 */
    public String part(int index) {
        if (absolute) {
            return index == 0 ? "/" : segments.get(index - 1);
        }
        return segments.get(index);
    }

    /** parts 是否包含指定组件（如 ".."）。 */
    public boolean partsContain(String value) {
        if (absolute && "/".equals(value)) {
            return true;
        }
        return segments.contains(value);
    }

    /** parts 全量组件列表（绝对路径首段为 "/"）。 */
    public java.util.List<String> parts() {
        java.util.List<String> parts = new ArrayList<>();
        if (absolute) {
            parts.add("/");
        }
        parts.addAll(segments);
        return parts;
    }

    /** as_posix()：折叠空段后的规范化 POSIX 字符串。 */
    public String asPosix() {
        return normalized;
    }

    /** 是否位于 ancestor 之下（对应 {@code ancestor in path.parents}）。 */
    public boolean isUnder(PosixPathLite ancestor) {
        if (!ancestor.absolute || !absolute || segments.size() <= ancestor.segments.size()) {
            return false;
        }
        return segments.subList(0, ancestor.segments.size()).equals(ancestor.segments);
    }

    /**
     * 以当前路径为基连接各组件（对应 {@code Path.joinpath(*parts)}，即 {@code Path(a) / b} 链）。
     *
     * <p>空基路径（{@code parse("")}）只返回组件本身；绝对路径基（{@code "/"}）不会产生 {@code //}。
     */
    public PosixPathLite join(List<String> parts) {
        List<String> merged = new ArrayList<>(segments);
        for (String part : parts) {
            PosixPathLite child = parse(part == null ? "" : part);
            if (child.absolute) {
                // 子路径为绝对路径时整体替换（与 pathlib 一致）
                merged = new ArrayList<>(child.segments);
                return new PosixPathLite(true, merged);
            }
            merged.addAll(child.segments);
        }
        return new PosixPathLite(absolute, merged);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PosixPathLite && ((PosixPathLite) other).normalized.equals(normalized);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(normalized);
    }

    @Override
    public String toString() {
        return normalized;
    }
}
