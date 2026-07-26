import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// Input bar — with voice/text toggle + text input + [+] menu.
/// Supports ask placeholder mode (triggered from parent).
class InputBar extends StatefulWidget {
  final ValueChanged<String> onSend;
  final bool hasActiveChat;          // true when chatting with AI
  final VoidCallback? onAskActivated; // called when ask mode starts typing

  const InputBar({
    super.key,
    required this.onSend,
    this.hasActiveChat = false,
    this.onAskActivated,
  });

  @override
  State<InputBar> createState() => _InputBarState();
}

class _InputBarState extends State<InputBar> {
  final TextEditingController _textCtrl = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  bool _isVoice = false;
  bool _hasText = false;
  bool _recording = false;

  static const _placeholders = [
    'record something...',
    'thinking about...',
    'jot something down...',
    'what is on your mind?',
    'any thoughts to record?',
    'what happened just now?',
  ];
  late final String _placeholder;

  @override
  void initState() {
    super.initState();
    final day = DateTime.now().day;
    _placeholder = _placeholders[day % _placeholders.length];
    _textCtrl.addListener(() {
      setState(() => _hasText = _textCtrl.text.trim().isNotEmpty);
    });
  }

  @override
  void didUpdateWidget(InputBar old) {
    super.didUpdateWidget(old);
    if (widget.hasActiveChat && !old.hasActiveChat) {
      // Ask mode just activated – focus input
      _focusNode.requestFocus();
    }
  }

  @override
  void dispose() {
    _textCtrl.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _toggleVoice() {
    setState(() => _isVoice = !_isVoice);
    if (!_isVoice) {
      _focusNode.requestFocus();
    } else {
      _focusNode.unfocus();
    }
  }

  void _send() {
    final text = _textCtrl.text.trim();
    if (text.isEmpty) return;
    widget.onSend(text);
    _textCtrl.clear();
  }

  void _showAttach(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (_) => Container(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        decoration: const BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Center(
              child: Container(
                width: 36, height: 4,
                margin: const EdgeInsets.only(bottom: 20),
                decoration: BoxDecoration(
                  color: AppColors.darkGrey5.withAlpha(76),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _attachItem(Icons.image_outlined, 'image'),
                _attachItem(Icons.mic_outlined, 'voice'),
                _attachItem(Icons.description_outlined, 'file'),
                _attachItem(Icons.link_outlined, 'link'),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _attachItem(IconData icon, String label) {
    return GestureDetector(
      onTap: () => Navigator.pop(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 52, height: 52,
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Icon(icon, color: AppColors.darkGrey4, size: 24),
          ),
          const SizedBox(height: 6),
          Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        border: Border(
          top: BorderSide(color: AppColors.darkBorder.withAlpha(76), width: 0.5),
        ),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 40,
          child: Row(
            children: [
              // Voice/text toggle
              GestureDetector(
                onTap: _toggleVoice,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: 40, height: 40,
                  decoration: BoxDecoration(
                    color: _isVoice ? AppColors.darkGrey1 : AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _isVoice ? Icons.keyboard_outlined : Icons.mic_outlined,
                    size: 20,
                    color: _isVoice ? AppColors.darkBg : AppColors.darkGrey4,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              // Input
              Expanded(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  child: _isVoice ? _buildVoice() : _buildText(),
                ),
              ),
              const SizedBox(width: 8),
              // Right button
              GestureDetector(
                onTap: _hasText && !_isVoice ? _send : () => _showAttach(context),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: 40, height: 40,
                  decoration: BoxDecoration(
                    color: !_isVoice && _hasText
                        ? (widget.hasActiveChat ? AppColors.darkGreen : AppColors.darkGrey1)
                        : AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    !_isVoice && _hasText ? Icons.arrow_upward_rounded : Icons.add_rounded,
                    size: 20,
                    color: !_isVoice && _hasText ? AppColors.darkBg : AppColors.darkGrey5,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildText() {
    return Container(
      key: const ValueKey('text'),
      height: 40,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(14),
        border: widget.hasActiveChat
            ? Border.all(color: AppColors.darkGreen.withAlpha(100), width: 0.5)
            : null,
      ),
      child: TextField(
        controller: _textCtrl,
        focusNode: _focusNode,
        style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1),
        decoration: InputDecoration(
          hintText: widget.hasActiveChat ? 'ask your question...' : _placeholder,
          hintStyle: TextStyle(
            fontSize: 15,
            color: widget.hasActiveChat
                ? AppColors.darkGreen.withAlpha(180)
                : AppColors.darkGrey6,
          ),
          border: InputBorder.none,
          filled: false,
          contentPadding: EdgeInsets.zero,
          isDense: true,
        ),
        onSubmitted: (_) => _send(),
      ),
    );
  }

  Widget _buildVoice() {
    return GestureDetector(
      key: const ValueKey('voice'),
      onLongPressStart: (_) => setState(() => _recording = true),
      onLongPressEnd: (_) {
        setState(() => _recording = false);
        widget.onSend('voice message');
      },
      child: Container(
        height: 40,
        decoration: BoxDecoration(
          color: _recording ? AppColors.darkGreen.withAlpha(20) : AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(14),
          border: _recording ? Border.all(color: AppColors.darkGreen.withAlpha(51), width: 0.5) : null,
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _recording ? Icons.mic : Icons.mic_none_outlined,
              size: 18,
              color: _recording ? AppColors.darkGreen : AppColors.darkGrey5,
            ),
            const SizedBox(width: 6),
            Text(
              _recording ? 'release to send' : 'hold to talk',
              style: TextStyle(fontSize: 14, color: _recording ? AppColors.darkGreen : AppColors.darkGrey5),
            ),
          ],
        ),
      ),
    );
  }
}
