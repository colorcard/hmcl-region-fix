/*
 * HMCL Region Fix
 * Copyright (C) 2026 ColorCard and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.colorcard.hmcl.regionfix;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * HMCL Region Fix Agent（适用于使用 RegionUtils 地区判断的受限制 HMCL 版本）
 *
 * 新版 HMCL 使用 RegionUtils.isIsChinaMainlandNetwork() 判断是否禁用
 * 中国大陆的下载功能。本 Agent 在内存中把 RegionUtils 的
 * isChinaMainlandNetwork 缓存字段设为 false，页面就不会进入禁用分支。
 *
 * 如果反射失败，则回退到修改 RegionUtils.class：
 *   "Asia/Shanghai" -> "Asia/ShanghaX"
 *   "CN"            -> "XX"
 *   并把 Windows GetUserGeoID() == 45 的分支改成 == 0。
 *
 * 不修改磁盘上的 HMCL jar，因此 HMCL 的自校验、自动更新、
 * Microsoft 登录都不会被破坏。
 */
public final class RegionFixAgent {

    private static final String REGION_UTILS = "org/jackhuang/hmcl/util/RegionUtils";

    private static final String ASIA_ORIG = "Asia/Shanghai";
    private static final String ASIA_PATCHED = "Asia/ShanghaX";
    private static final String CN_ORIG = "CN";
    private static final String CN_PATCHED = "XX";

    public static void premain(String args, Instrumentation inst) {
        boolean fieldPatched = false;

        if (!"true".equals(System.getProperty("hmcl.regionfix.no_reflect"))) {
            try {
                Class<?> region = Class.forName("org.jackhuang.hmcl.util.RegionUtils");
                Field field = region.getDeclaredField("isChinaMainlandNetwork");
                field.setAccessible(true);
                field.set(null, Boolean.FALSE);
                fieldPatched = true;
            } catch (Throwable ignored) {
                // RegionUtils 还没加载、或者字段名变了，走字节码回退
            }
        }

        if (fieldPatched) {
            return;
        }

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader,
                                    String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (!REGION_UTILS.equals(className)) {
                    return null;
                }
                try {
                    return patchRegionUtils(classfileBuffer) ? classfileBuffer : null;
                } catch (Throwable t) {
                    System.err.println("[HMCL Region Fix] failed to patch " + className + ": " + t);
                    return null;
                }
            }
        });
    }

    /**
     * 回退方案：把 RegionUtils 里的地区判断字符串换成等长假值，
     * 并把 Windows GeoID == 45（中国）的分支改成 == 0。
     */
    private static boolean patchRegionUtils(byte[] b) throws IOException {
        if (b.length < 10
                || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE
                || (b[2] & 0xFF) != 0xBA || (b[3] & 0xFF) != 0xBE) {
            throw new IOException("bad class file");
        }

        int cpCount = u2(b, 8);
        int pos = 10;
        boolean foundAsia = false;
        boolean foundCN = false;

        for (int i = 1; i < cpCount; i++) {
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1: { // Utf8
                    int len = u2(b, pos);
                    pos += 2;
                    if (len == ASIA_ORIG.length() && matches(b, pos, ASIA_ORIG)) {
                        replace(b, pos, ASIA_PATCHED);
                        foundAsia = true;
                    } else if (len == CN_ORIG.length() && matches(b, pos, CN_ORIG)) {
                        replace(b, pos, CN_PATCHED);
                        foundCN = true;
                    }
                    pos += len;
                    break;
                }
                case 3: // Integer
                case 4: // Float
                    pos += 4;
                    break;
                case 5: // Long
                case 6: // Double
                    pos += 8;
                    i++;
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    pos += 2;
                    break;
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    pos += 4;
                    break;
                case 15: // MethodHandle
                    pos += 3;
                    break;
                default:
                    throw new IOException("unknown constant pool tag: " + tag);
            }
        }

        // 把 bipush 45 改成 bipush 0，禁用 Windows GeoID == 45 分支
        for (int i = 0; i + 1 < b.length; i++) {
            if ((b[i] & 0xFF) == 0x10 && (b[i + 1] & 0xFF) == 0x2D) {
                b[i + 1] = 0x00;
                foundCN = true;
            }
        }

        return foundAsia || foundCN;
    }

    private static int u2(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static boolean matches(byte[] b, int pos, String s) {
        if (pos + s.length() > b.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if ((b[pos + i] & 0xFF) != (s.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static void replace(byte[] b, int pos, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[pos + i] = (byte) s.charAt(i);
        }
    }

    private RegionFixAgent() {
    }
}
