import 'package:local_auth/local_auth.dart';

/// 生物识别本地门禁（RFC 20260914 L2 登录体验批）。
///
/// **定位（别搞混）**：Face ID / Touch ID 只是「本机解锁闸」——它决定**这台设备上的
/// 这一次打开**要不要放行；凭证仍然是服务端会话 token（`Authorization: Bearer`）。
/// 生物特征**永不出设备**：服务端不接收、不存储、不校验，它也不构成第二因子。
///
/// 抽成接口是为了让 `RootApp` 的启动闸门可测：测试注入假实现，不碰真实 Face ID。
abstract class BiometricGate {
  /// 本机当前是否可用生物识别（未录入面容/指纹、被系统策略禁用 → false）。
  Future<bool> isAvailable();

  /// 弹出系统确认框。
  /// true = 通过；false = 取消/失败/不可用（调用方**回退密码登录**，不放行也不报错）。
  Future<bool> authenticate({String reason});
}

/// 默认实现：`local_auth`（iOS → LocalAuthentication / Face ID；Android → BiometricPrompt）。
class BiometricService implements BiometricGate {
  BiometricService({LocalAuthentication? auth}) : _auth = auth ?? LocalAuthentication();

  final LocalAuthentication _auth;

  @override
  Future<bool> isAvailable() async {
    try {
      if (!await _auth.isDeviceSupported()) return false;
      return await _auth.canCheckBiometrics;
    } catch (_) {
      // 任何异常一律按「不可用」处理 → 降级为密码登录，绝不阻塞启动
      return false;
    }
  }

  @override
  Future<bool> authenticate({String reason = '解锁阿呆阿呆'}) async {
    try {
      return await _auth.authenticate(
        localizedReason: reason,
        options: const AuthenticationOptions(
          // 切后台（来电/短信/切走再回来）后继续，而不是直接判失败
          stickyAuth: true,
          // 允许系统级回退到「设备锁屏密码」——那是 iOS 自己的兜底，不是我们的登录密码
          biometricOnly: false,
        ),
      );
    } catch (_) {
      return false;
    }
  }
}
