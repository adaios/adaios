// 非 web（VM 测试 / 原生平台）：无 XHR upload 进度通道 → 返回 null，调用方回落
// http MultipartRequest（无逐块进度，但不影响上传功能本身）。
library;

import 'tdx_upload_types.dart';

/// 平台不支持真实上传进度。
TdxProgressUploadFn? tdxProgressUpload() => null;
