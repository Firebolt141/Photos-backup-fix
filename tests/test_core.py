"""Unit + integration tests for core.py.

Run:  python -m pytest tests/
Integration tests need ExifTool on PATH (and ffmpeg for the video test);
they are skipped automatically when the tools are missing.
"""

import base64
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import core  # noqa: E402
from core import (  # noqa: E402
    DriveFixer, DuplicateFinder, ExifTool, FolderRenamer, Meta, PhotoDate, ProcessOptions,
    Processor, SidecarIndex, _ts_from_filename, build_tag_args, date_from_filename,
    date_subdir, find_duplicate_groups, json_candidate_names, parse_day_folder,
    parse_embedded_date, parse_meta, parse_month_folder, plan_folder_renames,
)

HAS_EXIFTOOL = core.find_exiftool() is not None
HAS_FFMPEG = shutil.which('ffmpeg') is not None
needs_exiftool = pytest.mark.skipif(not HAS_EXIFTOOL, reason='ExifTool not installed')

# 1×1 baseline JPEG
JPEG = base64.b64decode(
    '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////'
    '////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgB'
    'AQABPxA=')


def jpeg(path: Path, salt: bytes = b'') -> Path:
    """Write a valid JPEG; *salt* (a COM segment) makes the bytes unique."""
    path.parent.mkdir(parents=True, exist_ok=True)
    data = JPEG
    if salt:
        com = b'\xff\xfe' + (len(salt) + 2).to_bytes(2, 'big') + salt
        data = JPEG[:2] + com + JPEG[2:]
    path.write_bytes(data)
    return path


def sidecar(path: Path, ts=None, lat=None, lon=None, **extra) -> Path:
    data = dict(extra)
    if ts is not None:
        data['photoTakenTime'] = {'timestamp': str(ts)}
    if lat is not None:
        data['geoData'] = {'latitude': lat, 'longitude': lon, 'altitude': 10.0}
    path.write_text(json.dumps(data), encoding='utf-8')
    return path


def read_tags(path: Path, *tags) -> dict:
    out = subprocess.run(['exiftool', '-j', '-n', *[f'-{t}' for t in tags], str(path)],
                         capture_output=True, text=True).stdout
    return json.loads(out)[0]


# ── Filename dates (mirrors TakeoutProcessorTest.kt) ──────────────────────────

class TestFilenameDates:
    @pytest.mark.parametrize('name,expected', [
        ('IMG_20240315_143022.jpg', datetime(2024, 3, 15, 14, 30, 22)),
        ('PXL_20240315_143022123.jpg', datetime(2024, 3, 15, 14, 30, 22)),
        ('Screenshot_20240315-143022.png', datetime(2024, 3, 15, 14, 30, 22)),
        ('Screenshot_2024-03-15-14-30-22-123_com.app.jpg', datetime(2024, 3, 15, 14, 30, 22)),
        ('signal-2024-03-15-143022.jpg', datetime(2024, 3, 15, 14, 30, 22)),
        ('VID-20240315-WA0001.mp4', datetime(2024, 3, 15, 12, 0, 0)),
        ('20240315.jpg', datetime(2024, 3, 15, 12, 0, 0)),
        ('IMG_20401231_000000.jpg', datetime(2040, 12, 31, 0, 0, 0)),
    ])
    def test_parses(self, name, expected):
        pd = date_from_filename(name, tz='utc')
        assert pd is not None and pd.dt == expected and pd.source == 'filename'

    @pytest.mark.parametrize('name', [
        'IMG_19991231_000000.jpg', 'IMG_20991231_000000.jpg', 'IMG_20241300_000000.jpg',
        'IMG_20240230_000000.jpg', 'random_photo.jpg', 'DSC_1234.JPG', '12345678.jpg',
    ])
    def test_rejects(self, name):
        assert date_from_filename(name) is None

    def test_date_only_is_noon_utc_like_android(self):
        pd = date_from_filename('IMG-20240315-WA0001.jpg')
        assert pd.offset == '+00:00' and pd.dt.hour == 12

    def test_time_uses_local_offset_by_default(self):
        pd = date_from_filename('IMG_20240315_143022.jpg')
        assert pd.offset == core._local_offset(datetime(2024, 3, 15, 14, 30, 22))

    def test_invalid_time_falls_back_to_noon(self):
        assert date_from_filename('IMG_20240315_996022.jpg').dt == datetime(2024, 3, 15, 12)

    def test_epoch_millis(self):
        pd = date_from_filename('1710513022123.jpg')
        assert pd.dt == datetime(2024, 3, 15, 14, 30, 22)

    def test_back_compat_timestamp(self):
        assert _ts_from_filename('IMG_20240315_143022.jpg') == 1710504000   # 2024-03-15 12:00 UTC
        assert _ts_from_filename('nothing.jpg') is None


# ── Sidecars ──────────────────────────────────────────────────────────────────

class TestSidecarNames:
    def test_standard(self):
        n = json_candidate_names('IMG_20240315_143022.jpg')
        assert 'IMG_20240315_143022.jpg.json' in n and 'IMG_20240315_143022.json' in n

    def test_long_name_truncated(self):
        name = 'a' * 50 + '.jpg'
        assert f'{name[:46]}.json' in json_candidate_names(name)

    def test_numbered(self):
        n = json_candidate_names('photo(1).jpg')
        assert 'photo.jpg(1).json' in n and 'photo(1).json' in n

    def test_edited_other_languages(self):
        assert 'IMG_001.jpg.json' in json_candidate_names('IMG_001-edited.jpg')
        assert 'IMG_001.jpg.json' in json_candidate_names('IMG_001-bearbeitet.jpg')

    def test_supplemental(self):
        n = json_candidate_names('photo.jpg')
        assert 'photo.jpg.supplemental-metadata.json' in n


class TestSidecarIndex:
    def test_exact_supplemental(self, tmp_path):
        jpeg(tmp_path / 'a.jpg')
        j = sidecar(tmp_path / 'a.jpg.supplemental-metadata.json', 1)
        assert SidecarIndex().find(tmp_path / 'a.jpg') == j

    def test_abbreviated_supplemental(self, tmp_path):
        jpeg(tmp_path / 'IMG_1234.JPG')
        j = sidecar(tmp_path / 'IMG_1234.JPG.supplemental-me.json', 1)
        assert SidecarIndex().find(tmp_path / 'IMG_1234.JPG') == j

    def test_numbered_supplemental(self, tmp_path):
        jpeg(tmp_path / 'IMG_1(1).jpg')
        sidecar(tmp_path / 'IMG_1.jpg.supplemental-metadata.json', 1)
        j = sidecar(tmp_path / 'IMG_1.jpg.supplemental-metadata(1).json', 2)
        assert SidecarIndex().find(tmp_path / 'IMG_1(1).jpg') == j
        assert SidecarIndex().find(tmp_path / 'IMG_1.jpg') != j

    def test_truncated_at_46(self, tmp_path):
        name = 'a_really_long_file_name_that_google_truncates_xx.jpg'
        jpeg(tmp_path / name)
        j = sidecar(tmp_path / f'{name[:46]}.json', 1)
        assert SidecarIndex().find(tmp_path / name) == j

    def test_live_photo_video_uses_image_sidecar(self, tmp_path):
        (tmp_path / 'IMG_5.MP4').write_bytes(b'x')
        j = sidecar(tmp_path / 'IMG_5.HEIC.json', 1)
        assert SidecarIndex().find(tmp_path / 'IMG_5.MP4') == j

    def test_title_fallback(self, tmp_path):
        jpeg(tmp_path / 'renamed weirdly.jpg')
        j = sidecar(tmp_path / 'something-else.json', 1, title='renamed weirdly.jpg')
        assert SidecarIndex().find(tmp_path / 'renamed weirdly.jpg') == j

    def test_ignores_album_metadata(self, tmp_path):
        jpeg(tmp_path / 'metadata.jpg')
        (tmp_path / 'metadata.json').write_text('{"title": "album"}')
        assert SidecarIndex().find(tmp_path / 'metadata.jpg') is None

    def test_no_sidecar(self, tmp_path):
        jpeg(tmp_path / 'x.jpg')
        assert SidecarIndex().find(tmp_path / 'x.jpg') is None


class TestParseMeta:
    def parse(self, tmp_path, obj):
        p = tmp_path / 'm.json'
        p.write_text(obj if isinstance(obj, str) else json.dumps(obj), encoding='utf-8')
        return parse_meta(p)

    def test_photo_taken_priority(self, tmp_path):
        m = self.parse(tmp_path, {'creationTime': {'timestamp': '2000'}, 'photoTakenTime': {'timestamp': '1000'}})
        assert m.timestamp == 1000 and m.timestamp_source == 'photoTakenTime'

    def test_creation_fallback(self, tmp_path):
        m = self.parse(tmp_path, {'creationTime': {'timestamp': '1710500001'}})
        assert m.timestamp == 1710500001 and m.timestamp_source == 'creationTime'

    def test_geo(self, tmp_path):
        m = self.parse(tmp_path, {'geoDataExif': {'latitude': 1.0, 'longitude': 2.0, 'altitude': 3.0},
                                  'geoData': {'latitude': 5.0, 'longitude': 6.0}})
        assert (m.latitude, m.longitude, m.altitude) == (1.0, 2.0, 3.0)
        m = self.parse(tmp_path, {'geoDataExif': {'latitude': 0, 'longitude': 0},
                                  'geoData': {'latitude': 5.0, 'longitude': 6.0}})
        assert m.latitude == 5.0

    def test_people_favourite_description(self, tmp_path):
        m = self.parse(tmp_path, {'description': ' Sunset ', 'people': [{'name': 'Ann'}, {'x': 1}],
                                  'favorited': True})
        assert m.description == 'Sunset' and m.people == ['Ann'] and m.favorited

    @pytest.mark.parametrize('bad', ['not json {{', '[]', '{}',
                                     '{"photoTakenTime": {"timestamp": "abc"}}',
                                     '{"photoTakenTime": {"timestamp": "99999999999"}}'])
    def test_bad_input(self, tmp_path, bad):
        m = self.parse(tmp_path, bad)
        assert m.timestamp is None and m.latitude is None


# ── Layout ────────────────────────────────────────────────────────────────────

def test_date_subdir_matches_android_layout():
    assert date_subdir(datetime(2024, 1, 7)) == Path('2024/January/January_07')
    assert date_subdir(datetime(2024, 1, 7), numeric=True) == Path('2024/01/07')


@pytest.mark.parametrize('name,day', [('January_07', 7), ('January 15', 15), ('15', 15),
                                      ('January_32', None), ('notes', None)])
def test_parse_day_folder(name, day):
    assert parse_day_folder(name) == day


def test_parse_month_folder():
    assert parse_month_folder('march') == 3
    assert parse_month_folder('03') == 3
    assert parse_month_folder('13') is None


def test_parse_embedded_date():
    img = parse_embedded_date({'DateTimeOriginal': '2020:05:06 07:08:09', 'OffsetTimeOriginal': '+02:00'}, False)
    assert img.dt == datetime(2020, 5, 6, 7, 8, 9) and img.offset == '+02:00'
    assert parse_embedded_date({'DateTimeOriginal': '0000:00:00 00:00:00'}, False) is None
    vid = parse_embedded_date({'CreateDate': '2020:05:06 07:08:09'}, True)
    assert vid.offset == '+00:00'


# ── Tag building ──────────────────────────────────────────────────────────────

class TestTagArgs:
    def test_image_date_and_offset(self):
        a = build_tag_args(False, PhotoDate(datetime(2024, 3, 15, 14, 30), '+05:30', 'filename'))
        assert '-DateTimeOriginal=2024:03:15 14:30:00' in a
        assert '-OffsetTimeOriginal=+05:30' in a

    def test_video_dates_are_utc(self):
        a = build_tag_args(True, PhotoDate(datetime(2024, 3, 15, 14, 30), '+05:30', 'filename'))
        assert '-CreateDate=2024:03:15 09:00:00' in a
        assert '-Keys:CreationDate=2024:03:15 14:30:00+05:30' in a

    def test_gps_refs_and_escaping(self):
        m = Meta(latitude=-33.9, longitude=-151.2, altitude=-5.0, description='a\\b\nc',
                 people=['Zoë'], favorited=True)
        a = build_tag_args(False, PhotoDate.from_unix(0, 'sidecar'), m)
        assert '-GPSLatitudeRef=S' in a and '-GPSLongitudeRef=W' in a and '-GPSAltitudeRef=1' in a
        assert '-ImageDescription=a\\\\b\\nc' in a and a[0] == '-ec'
        assert '-XMP-iptcExt:PersonInImage=Zoë' in a and '-XMP:Rating=5' in a
        assert not any('\n' in x for x in a)

    def test_video_gps(self):
        a = build_tag_args(True, None, Meta(latitude=1.5, longitude=-2.5, altitude=3.0))
        assert '-Keys:GPSCoordinates=1.5, -2.5, 3.0' in a

    def test_options_disable_gps_and_extras(self):
        m = Meta(latitude=1.0, longitude=2.0, description='x', people=['p'])
        assert build_tag_args(False, None, m, write_gps=False, write_extras=False) == ['-ec']


# ── Folder renaming & duplicates (no ExifTool needed) ─────────────────────────

def test_plan_and_rename_with_merge(tmp_path):
    jpeg(tmp_path / '2024/01/15/a.jpg', b'1')
    jpeg(tmp_path / '2024/01/15/same.jpg', b'same')
    jpeg(tmp_path / '2024/01/7/b.jpg', b'2')
    jpeg(tmp_path / '2024/January/January_15/c.jpg', b'3')
    jpeg(tmp_path / '2024/January/January_15/same.jpg', b'same')
    jpeg(tmp_path / '2024/January/January_15/a.jpg', b'different')
    jpeg(tmp_path / '2024/February/February_30/x.jpg', b'4')    # not a real date: still renamed by number
    (tmp_path / '2024/January/February_07').mkdir(parents=True)  # month prefix disagrees: left alone

    plan = {(p.relative_to(tmp_path).as_posix(), n) for p, n in plan_folder_renames(tmp_path)}
    assert ('2024/01/15', 'January_15') in plan and ('2024/01/7', 'January_07') in plan
    assert ('2024/01', 'January') in plan
    assert not any(p.endswith('February_07') for p, _ in plan)

    dry = FolderRenamer(str(tmp_path), dry_run=True)
    assert dry.run() and (tmp_path / '2024/01').exists()

    job = FolderRenamer(str(tmp_path))
    assert job.run()
    j15 = tmp_path / '2024/January/January_15'
    assert sorted(p.name for p in j15.iterdir()) == ['a.jpg', 'a_1.jpg', 'c.jpg', 'same.jpg']
    assert (tmp_path / '2024/January/January_07/b.jpg').exists()
    assert not (tmp_path / '2024/01').exists()
    assert (tmp_path / '_duplicates/2024/01/January_15/same.jpg').exists()
    assert job.stats.conflicts == 1 and job.stats.errors == 0
    assert FolderRenamer(str(tmp_path)).run()          # idempotent


def test_duplicate_groups_keep_best_copy(tmp_path):
    a = jpeg(tmp_path / 'Takeout/Photos from 2020/IMG_1.jpg', b'x')
    sidecar(tmp_path / 'Takeout/Photos from 2020/IMG_1.jpg.json', 1)
    b = jpeg(tmp_path / 'Takeout/Album/IMG_1.jpg', b'x')
    c = jpeg(tmp_path / 'Takeout/Album/renamed copy.jpg', b'x')
    jpeg(tmp_path / 'Takeout/Album/other.jpg', b'y')
    groups = find_duplicate_groups([tmp_path])
    assert len(groups) == 1 and groups[0][0] == a and set(groups[0][1:]) == {b, c}

    job = DuplicateFinder([str(tmp_path)], move=True)
    assert job.run()
    assert job.stats.moved == 2 and a.exists() and not b.exists()
    assert (tmp_path / '_duplicates/Takeout/Album/IMG_1.jpg').exists()


# ── ExifTool integration ──────────────────────────────────────────────────────

@needs_exiftool
def test_exiftool_batch_roundtrip(tmp_path):
    f = jpeg(tmp_path / 'ü nicode.jpg')
    with ExifTool() as et:
        ok, msg = et.write(f, ['-ec', '-ImageDescription=line1\\nline2 \\\\ end'])
        assert ok, msg
        tags = et.read_tags([f, tmp_path / 'missing.jpg'], ['ImageDescription'])
    assert tags[core._norm_key(f)]['ImageDescription'] == 'line1\nline2 \\ end'


@needs_exiftool
class TestProcessor:
    def make_takeout(self, root: Path) -> Path:
        y = root / 'Takeout/Google Photos/Photos from 2019'
        jpeg(y / 'IMG_0001.JPG', b'1')
        sidecar(y / 'IMG_0001.JPG.supplemental-metadata.json', 1562236200, 48.85, 2.35,
                description='Paris', people=[{'name': 'Ann'}], favorited=True)
        jpeg(root / 'Takeout/Google Photos/Trip/IMG_0001.JPG', b'1')        # album copy
        sidecar(root / 'Takeout/Google Photos/Trip/IMG_0001.JPG.json', 1562236200, 48.85, 2.35,
                description='Paris', people=[{'name': 'Ann'}], favorited=True)   # albums repeat the sidecar
        jpeg(y / 'IMG_20190203_101112.jpg', b'2')                           # filename date
        jpeg(y / 'undated.jpg', b'3')
        jpeg(y / 'camera.jpg', b'4')
        subprocess.run(['exiftool', '-q', '-overwrite_original',
                        '-DateTimeOriginal=2018:05:05 08:00:00', str(y / 'camera.jpg')], check=True)
        sidecar(y / 'camera.jpg.json', 1600000000)                           # disagrees: file date kept
        (y / 'broken.jpg').write_bytes(b'not really a jpeg')
        sidecar(y / 'broken.jpg.json', 1562236200)
        return root

    def test_end_to_end(self, tmp_path):
        src = self.make_takeout(tmp_path / 'src')
        dst = tmp_path / 'out'
        job = Processor(str(src), str(dst), options=ProcessOptions(filename_tz='utc'))
        assert job.run()
        s = job.stats
        assert (s.total, s.from_sidecar, s.from_filename, s.kept_existing) == (6, 1, 1, 1)
        assert (s.no_json, s.skipped, s.meta_failed, s.errors) == (1, 1, 1, 0)

        out = dst / '2019/July/July_04/IMG_0001.JPG'
        t = read_tags(out, 'DateTimeOriginal', 'OffsetTimeOriginal', 'GPSLatitude', 'GPSLongitude',
                      'ImageDescription', 'PersonInImage', 'Rating')
        assert t['DateTimeOriginal'] == '2019:07:04 10:30:00' and t['OffsetTimeOriginal'] == '+00:00'
        assert abs(t['GPSLatitude'] - 48.85) < 1e-4 and t['ImageDescription'] == 'Paris'
        assert t['PersonInImage'] == 'Ann' and t['Rating'] == 5
        assert abs(out.stat().st_mtime - 1562236200) < 2

        fn = read_tags(dst / '2019/February/February_03/IMG_20190203_101112.jpg', 'DateTimeOriginal')
        assert fn['DateTimeOriginal'] == '2019:02:03 10:11:12'
        assert read_tags(dst / '2018/May/May_05/camera.jpg', 'DateTimeOriginal')['DateTimeOriginal'] \
            == '2018:05:05 08:00:00'
        assert (dst / 'no-date/undated.jpg').exists()
        assert (dst / '2019/July/July_04/broken.jpg').exists()          # kept despite ExifTool failure
        assert not list(dst.rglob('.~photofix~*'))
        assert job.report_path and job.report_path.with_suffix('.csv').exists()

        again = Processor(str(src), str(dst))
        assert again.run()
        assert again.stats.skipped == 6 and again.stats.bytes_copied == 0
        assert not list(dst.rglob('*_1.*'))

    def test_dry_run_writes_nothing_but_report(self, tmp_path):
        src = self.make_takeout(tmp_path / 'src')
        dst = tmp_path / 'out'
        job = Processor(str(src), str(dst), options=ProcessOptions(dry_run=True, output_mode='date_numeric'))
        assert job.run()
        assert [p.name for p in dst.iterdir()] == ['_photofix']
        assert any(r.dest.replace('\\', '/') == '2019/07/04/IMG_0001.JPG' for r in job.records)

    def test_same_name_different_photo_kept(self, tmp_path):
        src = tmp_path / 'src'
        jpeg(src / 'a/IMG_1.jpg', b'one')
        jpeg(src / 'b/IMG_1.jpg', b'two')
        sidecar(src / 'a/IMG_1.jpg.json', 1562236200)
        sidecar(src / 'b/IMG_1.jpg.json', 1562236200)
        job = Processor(str(src), str(tmp_path / 'out'))
        assert job.run()
        names = sorted(p.name for p in (tmp_path / 'out/2019/July/July_04').iterdir())
        assert names == ['IMG_1.jpg', 'IMG_1_1.jpg']

    def test_overwrite_existing_dates_option(self, tmp_path):
        src = self.make_takeout(tmp_path / 'src')
        job = Processor(str(src), str(tmp_path / 'out'), options=ProcessOptions(keep_existing_dates=False))
        assert job.run()
        assert (tmp_path / 'out/2020/September/September_13/camera.jpg').exists()

    def test_filename_mode_ignores_sidecars(self, tmp_path):
        src = self.make_takeout(tmp_path / 'src')
        job = Processor(str(src), str(tmp_path / 'out'), options=ProcessOptions(use_sidecars=False))
        assert job.run() and job.stats.from_sidecar == 0
        assert (tmp_path / 'out/no-date/IMG_0001.JPG').exists()

    def test_skip_files_and_stop(self, tmp_path):
        src = self.make_takeout(tmp_path / 'src')
        trip = src / 'Takeout/Google Photos/Trip/IMG_0001.JPG'
        job = Processor(str(src), str(tmp_path / 'out'), skip_files=frozenset([trip]))
        assert job.run() and job.stats.total == 5
        stopped = Processor(str(src), str(tmp_path / 'out2'))
        stopped.stop()
        assert stopped.run() is False

    @pytest.mark.skipif(not HAS_FFMPEG, reason='ffmpeg not installed')
    def test_video(self, tmp_path):
        src = tmp_path / 'src'
        src.mkdir()
        v = src / 'clip.mp4'
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=s=32x32:d=1',
                        '-pix_fmt', 'yuv420p', str(v)], check=True)
        sidecar(src / 'clip.mp4.json', 1562236200, 1.5, -2.5)
        job = Processor(str(src), str(tmp_path / 'out'), options=ProcessOptions(keep_existing_dates=False))
        assert job.run() and job.stats.from_sidecar == 1
        out = tmp_path / 'out/2019/July/July_04/clip.mp4'
        t = read_tags(out, 'QuickTime:CreateDate', 'Keys:CreationDate', 'GPSCoordinates')
        assert t['CreateDate'] == '2019:07:04 10:30:00'
        assert t['CreationDate'].startswith('2019:07:04 10:30:00')


@needs_exiftool
def test_drive_fixer(tmp_path):
    a = jpeg(tmp_path / '2021/March/March_09/a.jpg', b'a')
    b = jpeg(tmp_path / '2021/03/10/IMG_20210310_204500.jpg', b'b')
    c = jpeg(tmp_path / '2021/March/March 11/c.jpg', b'c')
    subprocess.run(['exiftool', '-q', '-overwrite_original', '-DateTimeOriginal=2019:01:01 00:00:00', str(c)],
                   check=True)
    (tmp_path / '2021/March/March_09/clip.avi').write_bytes(b'RIFF')
    jpeg(tmp_path / 'Unsorted/x.jpg')

    dry = DriveFixer(str(tmp_path), dry_run=True)
    assert dry.run() and dry.stats.fixed == 2
    assert 'DateTimeOriginal' not in read_tags(a, 'DateTimeOriginal')

    job = DriveFixer(str(tmp_path), filename_tz='utc')
    assert job.run()
    s = job.stats
    assert (s.total, s.fixed, s.already_dated, s.mismatched, s.unsupported) == (4, 2, 1, 1, 1)
    assert read_tags(a, 'DateTimeOriginal')['DateTimeOriginal'] == '2021:03:09 12:00:00'
    assert read_tags(b, 'DateTimeOriginal')['DateTimeOriginal'] == '2021:03:10 20:45:00'
    assert read_tags(c, 'DateTimeOriginal')['DateTimeOriginal'] == '2019:01:01 00:00:00'   # untouched

    again = DriveFixer(str(tmp_path))
    assert again.run() and again.stats.fixed == 0 and again.stats.already_dated == 3


@needs_exiftool
def test_year_folder_as_root(tmp_path):
    a = jpeg(tmp_path / '2014/January/January_02/a.jpg')
    job = DriveFixer(str(tmp_path / '2014'))
    assert job.run() and job.stats.fixed == 1
    assert read_tags(a, 'DateTimeOriginal')['DateTimeOriginal'] == '2014:01:02 12:00:00'


# ── New options ───────────────────────────────────────────────────────────────

@needs_exiftool
def test_date_range_kinds_and_rename_to_date(tmp_path):
    src = tmp_path / 'src'
    jpeg(src / 'IMG_20190101_080000.jpg', b'a')
    jpeg(src / 'IMG_20200615_093000.jpg', b'b')
    jpeg(src / 'IMG_20210101_100000.jpg', b'c')
    jpeg(src / 'nodate.jpg', b'd')
    (src / 'VID_20200615_120000.avi').write_bytes(b'RIFF')
    opts = ProcessOptions(use_sidecars=False, filename_tz='utc', kinds='photos',
                          date_from='2020-01-01', date_to='2020-12-31', rename_to_date=True)
    job = Processor(str(src), str(tmp_path / 'out'), options=opts)
    assert job.run()
    assert job.stats.total == 4                      # the .avi was excluded up front
    assert job.stats.filtered == 3                   # 2019, 2021 and the undated file
    assert (tmp_path / 'out/2020/June/June_15/2020-06-15_09-30-00.jpg').exists()
    assert not (tmp_path / 'out/no-date').exists()
    again = Processor(str(src), str(tmp_path / 'out'), options=opts)
    assert again.run() and again.stats.skipped == 1


@needs_exiftool
def test_drive_fixer_corrects_mismatched_dates(tmp_path):
    c = jpeg(tmp_path / '2021/March/March_11/c.jpg', b'c')
    subprocess.run(['exiftool', '-q', '-overwrite_original', '-DateTimeOriginal=2019:01:01 07:15:00', str(c)],
                   check=True)
    job = DriveFixer(str(tmp_path), fix_mismatched=True)
    assert job.run()
    assert (job.stats.mismatched, job.stats.corrected) == (1, 1)
    assert read_tags(c, 'DateTimeOriginal')['DateTimeOriginal'] == '2021:03:11 07:15:00'
    assert DriveFixer(str(tmp_path)).run()
