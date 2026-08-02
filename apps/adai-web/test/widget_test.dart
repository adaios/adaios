import 'package:flutter_test/flutter_test.dart';
import 'package:adai_web/main.dart';

void main() {
  group('userId query 解析', () {
    test('无 userId → default', () {
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081')), 'default');
    });

    test('合法 userId → 透传', () {
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=alice')), 'alice');
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=adai-01')), 'adai-01');
    });

    test('非法 userId（路径注入）→ default', () {
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=../../etc')), 'default');
      expect(resolveUserIdFrom(Uri.parse('http://localhost:8081/?userId=a%2Fb')), 'default');
    });
  });
}
