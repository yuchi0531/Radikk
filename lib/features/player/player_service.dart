import 'dart:async';
import 'package:audio_session/audio_session.dart';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import '../../core/constants/device_config.dart';

/// 再生状態のenum
enum PlayerStatus { idle, loading, playing, paused, stopped, error }

/// プレイヤーサービス（just_audioラッパー）
class PlayerService {
  final AudioPlayer _player = AudioPlayer();

  PlayerStatus _status = PlayerStatus.idle;
  String? _currentStationId;
  String? _currentProgramTitle;
  StreamSubscription<PlayerState>? _stateSubscription;
  StreamSubscription<Duration?>? _positionSubscription;
  StreamSubscription<PlaybackEvent>? _eventSubscription;

  // コールバック
  void Function(PlayerStatus status)? onStatusChanged;
  void Function(Duration position, Duration duration)? onPositionChanged;
  void Function(String message)? onError;

  PlayerService() {
    _stateSubscription = _player.playerStateStream.listen(_handlePlayerState);
    _positionSubscription = _player.positionStream.listen((pos) {
      final duration = _player.duration;
      if (duration != null) {
        onPositionChanged?.call(pos, duration);
      }
    });
    // 再生中のストリームエラーを検知する（HLSのセグメント取得失敗など）
    // 再生エラーは playbackEventStream にエラーとして流れる
    _eventSubscription = _player.playbackEventStream.listen(
      (_) {},
      onError: (Object e) {
        onError?.call('再生エラー: $e');
        _updateStatus(PlayerStatus.error);
      },
    );
    _initAudioSession();
  }

  Future<void> _initAudioSession() async {
    try {
      final session = await AudioSession.instance;
      await session.configure(const AudioSessionConfiguration(
        avAudioSessionCategory: AVAudioSessionCategory.playback,
        avAudioSessionCategoryOptions:
            AVAudioSessionCategoryOptions.mixWithOthers,
        avAudioSessionMode: AVAudioSessionMode.defaultMode,
        avAudioSessionRouteSharingPolicy:
            AVAudioSessionRouteSharingPolicy.defaultPolicy,
        avAudioSessionSetActiveOptions: AVAudioSessionSetActiveOptions.none,
        androidAudioAttributes: AndroidAudioAttributes(
          contentType: AndroidAudioContentType.music,
          usage: AndroidAudioUsage.media,
        ),
        androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
        androidWillPauseWhenDucked: true,
      ));
    } catch (e) {
      // オーディオセッション設定の失敗は再生継続可能なのでログのみ
      debugPrint('[PlayerService] AudioSession設定失敗: $e');
    }
  }

  void _handlePlayerState(PlayerState state) {
    if (state.processingState == ProcessingState.completed) {
      // ストリームの終端に到達（ライブ配信終了・タイムフリーの最後）
      _updateStatus(PlayerStatus.stopped);
    } else if (state.playing) {
      _updateStatus(PlayerStatus.playing);
    } else if (state.processingState == ProcessingState.loading ||
        state.processingState == ProcessingState.buffering) {
      _updateStatus(PlayerStatus.loading);
    } else if (state.processingState == ProcessingState.ready ||
        state.processingState == ProcessingState.idle) {
      // playing == false かつ ready または idle なら一時停止
      _updateStatus(state.processingState == ProcessingState.idle
          ? PlayerStatus.stopped
          : PlayerStatus.paused);
    }
  }

  void _updateStatus(PlayerStatus newStatus) {
    if (_status != newStatus) {
      _status = newStatus;
      onStatusChanged?.call(_status);
    }
  }

  /// ライブストリームを再生
  Future<void> playLive({
    required String url,
    required String authToken,
    String? areaId,
    String? stationId,
    String? programTitle,
  }) async {
    try {
      _currentStationId = stationId;
      _currentProgramTitle = programTitle;
      _updateStatus(PlayerStatus.loading);

      // HLSストリームとして再生（just_audioがm3u8をネイティブで処理）
      // 認証には X-Radiko-AuthToken と X-Radiko-AreaId ヘッダーが必須（検証済み）
      await _player.setAudioSource(
        HlsAudioSource(
          Uri.parse(url),
          headers: {
            'X-Radiko-AuthToken': authToken,
            'X-Radiko-AreaId': ?areaId,
            'User-Agent': DeviceConfig.userAgent,
          },
        ),
      );
      await _player.play();
    } catch (e) {
      _updateStatus(PlayerStatus.error);
      rethrow;
    }
  }

  /// タイムフリーを再生
  Future<void> playTimefree({
    required String url,
    required String authToken,
    String? areaId,
    String? stationId,
    String? programTitle,
  }) async {
    await playLive(
      url: url,
      authToken: authToken,
      areaId: areaId,
      stationId: stationId,
      programTitle: programTitle,
    );
  }

  /// 一時停止
  Future<void> pause() async {
    await _player.pause();
    _updateStatus(PlayerStatus.paused);
  }

  /// 再開
  Future<void> resume() async {
    await _player.play();
  }

  /// 停止
  Future<void> stop() async {
    await _player.stop();
    _updateStatus(PlayerStatus.stopped);
    _currentStationId = null;
    _currentProgramTitle = null;
  }

  /// シーク
  Future<void> seek(Duration position) async {
    await _player.seek(position);
  }

  /// 再生中かどうか
  bool get isPlaying => _player.playing;

  /// 現在の再生位置
  Stream<Duration> get positionStream => _player.positionStream;

  /// 現在の再生位置（同期取得）
  Duration get position => _player.position;

  /// 再生時間
  Duration? get duration => _player.duration;

  /// 音量（0.0〜1.0）
  Future<void> setVolume(double volume) async {
    await _player.setVolume(volume);
  }

  /// 現在のステータス
  PlayerStatus get status => _status;

  /// 現在の放送局ID
  String? get currentStationId => _currentStationId;

  /// 現在の番組タイトル
  String? get currentProgramTitle => _currentProgramTitle;

  /// 解放
  Future<void> dispose() async {
    _stateSubscription?.cancel();
    _positionSubscription?.cancel();
    _eventSubscription?.cancel();
    await _player.dispose();
  }
}
