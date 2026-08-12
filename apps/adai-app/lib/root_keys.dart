import 'package:flutter/material.dart';

/// 根 ScaffoldMessengerKey（REVIEW #246）：MainPage 被 dispose（切 World B）后
/// 仍能弹失败/成功提示，不依赖 MainPage State 存活。
/// 在 main.dart 的 MaterialApp 挂载，MainPage 等任意层可经它弹 SnackBar。
final GlobalKey<ScaffoldMessengerState> rootScaffoldMessengerKey =
    GlobalKey<ScaffoldMessengerState>();
