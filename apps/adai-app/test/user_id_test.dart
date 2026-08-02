import 'package:flutter_test/flutter_test.dart';

import 'package:adai_app/main.dart';

void main() {
  group('resolveUserIdFrom', () {
    test('缺失 → default', () {
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081')), 'default');
      expect(
          resolveUserIdFrom(Uri.parse('http://localhost:8081/?foo=bar')),
          'default');
    });

    test('合法 userId → 原样', () {
      expect(
          resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=alice')),
          'alice');
      expect(
          resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=adai')),
          'adai');
      expect(
          resolveUserIdFrom(
              Uri.parse('http://localhost:8081/?userId=user_2-b')),
          'user_2-b');
    });

    test('非法（路径注入/空/含空格）→ default', () {
      expect(
          resolveUserIdFrom(
              Uri.parse('http://localhost:8081/?userId=..%2Fetc')),
          'default');
      expect(
          resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=')),
          'default');
      expect(
          resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=a%20b')),
          'default');
    });
  });
}
