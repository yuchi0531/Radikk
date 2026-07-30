import 'package:equatable/equatable.dart';

class Area extends Equatable {
  final String id;
  final String japanese;
  final String roman;

  const Area({required this.id, required this.japanese, required this.roman});

  factory Area.fromXml(Map<String, String> attrs) {
    return Area(
      id: attrs['area_id'] ?? '',
      japanese: attrs['name'] ?? '',
      roman: attrs['en_name'] ?? '',
    );
  }

  @override
  List<Object?> get props => [id, japanese, roman];
}
