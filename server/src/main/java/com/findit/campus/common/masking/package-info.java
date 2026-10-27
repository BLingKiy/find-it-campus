/**
 * 输出层遮蔽的单点实现（A1 §6）。
 *
 * <p>只依赖 JDK：遮蔽是纯函数，不该为了打几个正则去依赖 web 或 DB 层。
 * 所有出口（record / match / my / notification）只允许调用这里，不允许各自写规则副本。
 */
package com.findit.campus.common.masking;
