import 'package:flutter/foundation.dart';

import 'api_service.dart';
import 'share_extension_service.dart';

/// ShareTokenKeeper — 让「分享面板里的阿呆阿呆」**永远有钥匙**，且用户零操作
/// （RFC `20260915-share-extension-credentials.md`）。
///
/// **为什么需要它**：iOS 分享扩展是**独立进程**，读不到主 App 的存储，却要在无人值守下把
/// 链接提交给后端 —— 所以必须有凭据。此前这把**限权令牌**（`learn:digest`）要用户跑到学习页
/// 手点一次「签发」才写进共享容器：自己侧载时签过一次就一直能用，可**全新安装**（尤其
/// **给别人用**）就会卡在「分享报没拿到钥匙，但完全不知道该干什么」。
/// 本类把那一步自动化：
///
/// - **没钥匙** → 签发一把并写进共享容器
/// - **快到期**（剩余 < 7 天）→ 换一把新的
/// - **老数据没记到期时间**（升级上来的）→ 补签一次，顺手把到期时间带上，此后判断才准
///
/// **三条硬约束**：
/// 1. **绝不阻塞登录**：任何异常都吞掉 —— 这是后台自愈，失败就下次启动再试。
/// 2. **顺序不可颠倒**：必须先确认新钥匙**写进容器**，再撤销旧的。反过来的话，一旦写入失败
///    两把都没了，分享直接不可用（宁可短暂多一把，也不能两把都失效）。
/// 3. **不碰登录会话**：只签 `learn:digest` 限权令牌 —— 扩展永远拿不到会话 token（那是全权凭据）。
class ShareTokenKeeper {
  ShareTokenKeeper._();

  /// 自动签发用的标签（用户在学习页的令牌列表里能认出这把是系统放的）。
  static const String label = '系统分享（自动）';

  /// 剩余有效期少于这个值就换新的。
  static const Duration renewBefore = Duration(days: 7);

  /// 防重入：登录钩子与启动钩子可能几乎同时触发，重复签发会白造令牌。
  static Future<void>? _inFlight;

  /// 确保共享容器里有一把「还能用」的钥匙。可在任意时机安全调用（幂等、静默、不抛异常）。
  static Future<void> ensure(ApiService api) {
    final running = _inFlight;
    if (running != null) return running;
    final future = _run(api);
    _inFlight = future;
    return future.whenComplete(() => _inFlight = null);
  }

  static Future<void> _run(ApiService api) async {
    if (!ShareExtensionService.supported) return;
    try {
      final status = await ShareExtensionService.status();
      // 容器本身不可用（Entitlements / 签名没配好）→ 这不是「缺钥匙」，
      // 再怎么签发也放不进去，交给学习页的弹窗去说人话。
      if (!status.available) return;
      if (!needsIssue(status)) return;

      final oldId = status.id;
      final data = await api.issueExternalToken(label: label);
      final plain = data['token']?.toString();
      if (plain == null || plain.isEmpty) return; // 后端没给明文 = 这次没发出去

      final newId = data['id']?.toString();
      final expiresAt = data['expiresAt']?.toString();

      final saved = await ShareExtensionService.saveToken(
        token: plain,
        id: newId,
        expiresAt: expiresAt,
      );
      if (!saved) return; // 没写进容器就绝不能撤旧的（约束 2）

      if (oldId != null && oldId.isNotEmpty && oldId != newId) {
        try {
          await api.revokeExternalToken(oldId);
        } catch (_) {
          // 旧钥匙没撤掉不影响使用：它同样只有 learn:digest 权限，且到期自然失效。
          // 宁可留一把孤儿，也不因为「撤销失败」把刚接好的链路回滚掉。
        }
      }
    } catch (_) {
      // 静默：后台自愈失败绝不能冒泡到登录流程或 UI（约束 1）
    }
  }

  /// 需不需要（重新）签发。抽成纯函数是为了能单测——判断逻辑比过程更容易出错。
  @visibleForTesting
  static bool needsIssue(ShareExtensionStatus status) {
    if (!status.hasToken) return true;

    final expiresAt = status.expiresAt;
    if (expiresAt == null || expiresAt.isEmpty) {
      // 容器里有钥匙但没记到期时间 = 从旧版本升上来的 → 补签一次把到期时间带上，
      // 之后就能正常判断临期（这也让「无到期时间」这个状态自我消失）。
      return true;
    }
    final expiry = DateTime.tryParse(expiresAt);
    if (expiry == null) return true;
    return DateTime.now().toUtc().isAfter(expiry.toUtc().subtract(renewBefore));
  }
}
