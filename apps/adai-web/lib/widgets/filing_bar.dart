import 'package:flutter/material.dart';

import '../theme/app_colors.dart';
import '../utils/open_url.dart';

/// 底部备案栏——两套备案编号并列（法定要求：网站底部悬挂备案号并链接到官方查询页）。
///
/// - **工信部 ICP**：`京ICP备2026056893号` → <https://beian.miit.gov.cn>
/// - **公安部联网备案**（2026-09-24 通过）：`京公网安备11011402057309号`
///   → 平台查询页（`code` 取自备案平台给出的 HTML 代码，勿手改）
///
/// 图标来自公安备案平台下载的「备案图标」（36×40 PNG → `assets/images/gongan.png`）；
/// 图标加载不到时只省掉图，编号与链接照常显示——合规要点是**编号 + 链接**。
class FilingBar extends StatelessWidget {
  const FilingBar({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 26,
      decoration: const BoxDecoration(
        color: AppColors.darkSurface,
        border: Border(top: BorderSide(color: AppColors.darkBorder, width: 1)),
      ),
      child: const Center(child: FilingLinks()),
    );
  }
}

/// 只有内容、没有容器：登录页（底部无横条）复用同一份编号与链接，避免两处口径漂移。
class FilingLinks extends StatelessWidget {
  const FilingLinks({super.key});

  /// 公安部联网备案号 + 查询链接（取自备案平台给出的 HTML 代码）。
  static const String gonganNo = '京公网安备11011402057309号';
  static const String gonganUrl =
      'https://beian.mps.gov.cn/#/query/webSearch?code=11011402057309';

  /// 工信部 ICP 备案号 + 查询链接。
  static const String icpNo = '京ICP备2026056893号';
  static const String icpUrl = 'https://beian.miit.gov.cn';

  static const TextStyle _label = TextStyle(
    fontSize: 10,
    color: AppColors.darkGrey5,
    letterSpacing: 0.5,
  );

  @override
  Widget build(BuildContext context) {
    // 窄屏（手机浏览器 / 小登录卡）按比例缩，不让备案栏溢出。
    return FittedBox(
      fit: BoxFit.scaleDown,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _link(
            gonganUrl,
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Image.asset(
                  'assets/images/gongan.png',
                  height: 14,
                  width: 13,
                  filterQuality: FilterQuality.high,
                  errorBuilder: (_, _, _) => const SizedBox.shrink(),
                ),
                const SizedBox(width: 4),
                const Text(gonganNo, style: _label),
              ],
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 4),
            child: Text('·', style: _label),
          ),
          _link(icpUrl, const Text(icpNo, style: _label)),
        ],
      ),
    );
  }

  Widget _link(String url, Widget child) {
    return InkWell(
      onTap: () => openUrl(url),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 3),
        child: child,
      ),
    );
  }
}
