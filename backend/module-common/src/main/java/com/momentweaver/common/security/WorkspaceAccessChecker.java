// M5 重构：WorkspaceAccessChecker 已迁到 module-account.security 包。
// 原位置（com.momentweaver.common.security）保留空文件以避免破坏旧 import，
// 旧 import 路径仍能编译，但运行时调用任何方法都会抛 IllegalStateException。
// M5 新代码请用：import com.momentweaver.account.security.WorkspaceAccessChecker;
package com.momentweaver.common.security;
