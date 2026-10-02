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
        assert (dst / 'no-date/Takeout/Google Photos/Photos from 2019/undated.jpg').exists()
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
        assert (tmp_path / 'out/no-date/Takeout/Google Photos/Photos from 2019/IMG_0001.JPG').exists()

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


# ── Robustness: every file ends up somewhere sensible ─────────────────────────

import errno  # noqa: E402
import io  # noqa: E402
import tarfile  # noqa: E402
import zipfile  # noqa: E402

from core import (  # noqa: E402
    ArchiveExtractor, analyze_folder, collect_dated_files, find_archives, friendly_os_error,
    iter_media, safe_member_path,
)


def test_iter_media_skips_junk_and_reports_others(tmp_path):
    jpeg(tmp_path / 'a/IMG_1.jpg')
    (tmp_path / 'a/._IMG_1.jpg').write_bytes(b'appledouble')
    jpeg(tmp_path / '@eaDir/thumb.jpg')
    jpeg(tmp_path / '.thumbnails/t.jpg')
    (tmp_path / 'a/receipt.pdf').write_bytes(b'x')
    (tmp_path / 'a/Thumbs.db').write_bytes(b'x')
    (tmp_path / 'a/IMG_1.jpg.json').write_text('{}')
    jpeg(tmp_path / 'b/RAW.CR3')
    ignored = {}
    files = iter_media(tmp_path, ignored=ignored)
    assert sorted(p.name for p in files) == ['IMG_1.jpg', 'RAW.CR3']
    assert ignored == {'.pdf': 1}


def test_iter_media_reports_unreadable_folders(tmp_path, monkeypatch):
    jpeg(tmp_path / 'ok/a.jpg')
    (tmp_path / 'locked').mkdir()
    real = os.scandir

    def fake(path='.'):
        if str(path).endswith('locked'):
            raise PermissionError(13, 'Permission denied', str(path))
        return real(path)
    monkeypatch.setattr(os, 'scandir', fake)
    errs = []
    assert [p.name for p in iter_media(tmp_path, errors=errs)] == ['a.jpg']
    assert len(errs) == 1 and 'locked' in errs[0]


@pytest.mark.parametrize('err,text', [
    (OSError(errno.ENOSPC, 'x'), 'full'), (OSError(errno.EFBIG, 'x'), 'FAT32'),
    (PermissionError(errno.EACCES, 'x'), 'Permission'), (FileNotFoundError(errno.ENOENT, 'x'), 'disconnected'),
])
def test_friendly_os_error(err, text):
    assert text in friendly_os_error(err)


@needs_exiftool
class TestEveryFileLands:
    def test_empty_file_and_undated_keep_their_folders(self, tmp_path):
        src = tmp_path / 'src'
        jpeg(src / 'Holiday 2015/Day 1/beach.jpg', b'u')                   # no date anywhere
        (src / 'Scans/broken.jpg').parent.mkdir(parents=True)
        (src / 'Scans/broken.jpg').write_bytes(b'')                       # empty
        job = Processor(str(src), str(tmp_path / 'out'), options=ProcessOptions(use_sidecars=False))
        assert job.run()
        assert (tmp_path / 'out/no-date/Holiday 2015/Day 1/beach.jpg').exists()
        assert (tmp_path / 'out/error/Scans/broken.jpg').exists()
        assert job.stats.errors == 1 and job.stats.no_json == 1

    def test_renamed_identical_copy_kept_once(self, tmp_path):
        src = tmp_path / 'src'
        jpeg(src / 'a/IMG_20200101_120000.jpg', b'same')
        jpeg(src / 'b/IMG_20200101_120000 copy.jpg', b'same')      # same date from name, same bytes
        jpeg(src / 'c/IMG_20200101_120000 (2).jpg', b'other')      # same day, different photo
        out = tmp_path / 'out'
        job = Processor(str(src), str(out), options=ProcessOptions(use_sidecars=False, filename_tz='utc'))
        assert job.run() and job.stats.skipped == 1
        day = out / '2020/January/January_01'
        assert len(list(day.iterdir())) == 2
        # Re-run with renamed output names: content already there → skipped, not duplicated
        again = Processor(str(src), str(out), options=ProcessOptions(use_sidecars=False, filename_tz='utc',
                                                                     rename_to_date=True))
        assert again.run() and again.stats.skipped == 3 and len(list(day.iterdir())) == 2
        # A file deleted from the output is copied again on the next run
        victim = sorted(day.iterdir())[0]
        victim.unlink()
        third = Processor(str(src), str(out), options=ProcessOptions(use_sidecars=False, filename_tz='utc'))
        assert third.run() and len(list(day.iterdir())) == 2

    def test_disk_full_stops_the_run(self, tmp_path, monkeypatch):
        src = tmp_path / 'src'
        for i in range(12):
            jpeg(src / f'IMG_2020010{i % 9 + 1}_120000_{i}.jpg', bytes([i]))
        calls = {'n': 0}
        real = shutil.copy2

        def full(a, b, *k, **kw):
            calls['n'] += 1
            if calls['n'] > 2:
                raise OSError(errno.ENOSPC, 'No space left on device')
            return real(a, b, *k, **kw)
        monkeypatch.setattr(core.shutil, 'copy2', full)
        job = Processor(str(src), str(tmp_path / 'out'),
                        options=ProcessOptions(use_sidecars=False, keep_existing_dates=False), workers=1)
        assert job.run() is False
        assert 'full' in job.fatal
        assert job.stats.errors < 12                    # stopped early instead of failing everything
        assert not list((tmp_path / 'out').rglob('.~photofix~*'))


def test_collect_dated_files_nested_loose_and_locked(tmp_path, monkeypatch):
    jpeg(tmp_path / '2020/March/March_02/a.jpg')
    jpeg(tmp_path / '2020/March/March_02/Burst/b.jpg')
    jpeg(tmp_path / '2020/March/loose.jpg')
    jpeg(tmp_path / '2020/April/April_01/c.jpg')
    (tmp_path / '2020/March/March_02/._a.jpg').write_bytes(b'x')
    real = core._list_dir

    def flaky(d, notes=None):
        if d.name == 'April':
            if notes is not None:
                notes.append(f'could not open {d}: Permission denied')
            return [], []
        return real(d, notes)
    monkeypatch.setattr(core, '_list_dir', flaky)
    items, notes = collect_dated_files(tmp_path)
    assert sorted(p.name for p, _ in items) == ['a.jpg', 'b.jpg']
    assert any('no day folder' in n for n in notes) and any('Permission' in n for n in notes)


# ── Archives ──────────────────────────────────────────────────────────────────

@pytest.mark.parametrize('name,expected', [
    ('Takeout/Google Photos/a.jpg', 'Takeout/Google Photos/a.jpg'),
    ('../../evil.jpg', None), ('/etc/passwd', 'etc/passwd'), ('a/../../b.jpg', None),
    ('Takeout/what?.jpg', 'Takeout/what_.jpg'), ('C:\\Windows\\x.jpg', 'Windows/x.jpg'), ('', None),
])
def test_safe_member_path(name, expected):
    got = safe_member_path(name)
    assert (got.as_posix() if got else None) == expected


def _make_takeout_zips(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(d / 'takeout-001.zip', 'w') as z:
        z.writestr('Takeout/Google Photos/Photos from 2019/IMG_1.jpg', JPEG)
        z.writestr('Takeout/Google Photos/Photos from 2019/IMG_1.jpg.json', json.dumps({'photoTakenTime': {'timestamp': '1562236200'}}))
        z.writestr('../escape.jpg', JPEG)
    with zipfile.ZipFile(d / 'takeout-002.zip', 'w') as z:
        z.writestr('Takeout/Google Photos/Photos from 2020/IMG_2.jpg', JPEG + b'2')
    good = (d / 'takeout-002.zip').read_bytes()
    (d / 'takeout-003.zip').write_bytes(good[:len(good) // 2])
    with tarfile.open(d / 'takeout-004.tgz', 'w:gz') as t:
        ti = tarfile.TarInfo('Takeout/Google Photos/Photos from 2021/IMG_3.jpg')
        ti.size = len(JPEG)
        t.addfile(ti, io.BytesIO(JPEG))


def test_archive_extractor(tmp_path):
    _make_takeout_zips(tmp_path / 'dl')
    arcs = find_archives(tmp_path / 'dl')
    assert len(arcs) == 4
    out = tmp_path / 'unpacked'
    job = ArchiveExtractor([str(a) for a in arcs], str(out))
    assert job.run() is False                          # one archive is damaged
    s = job.stats
    assert (s.archives_ok, s.archives_bad, s.extracted) == (3, 1, 4)
    assert (out / 'Takeout/Google Photos/Photos from 2021/IMG_3.jpg').exists()
    assert not (tmp_path / 'escape.jpg').exists() and not (out.parent / 'escape.jpg').exists()
    good = [str(a) for a in arcs if '003' not in a.name]
    again = ArchiveExtractor(good, str(out))
    assert again.run() and again.stats.extracted == 0 and again.stats.skipped == 4


# ── Folder analysis ───────────────────────────────────────────────────────────

def test_analyze_takeout(tmp_path):
    for i in range(6):
        jpeg(tmp_path / f'Takeout/Google Photos/Photos from 2019/IMG_{i}.jpg', bytes([i]))
        sidecar(tmp_path / f'Takeout/Google Photos/Photos from 2019/IMG_{i}.jpg.json', 1)
    a = analyze_folder(tmp_path)
    assert a['kind'] == 'takeout' and a['recommendations'][0]['tool'] == 'takeout'


def test_analyze_archives(tmp_path):
    _make_takeout_zips(tmp_path)
    a = analyze_folder(tmp_path)
    assert a['kind'] == 'archives' and a['recommendations'][0]['tool'] == 'unpack'


def test_analyze_drive_with_legacy_folders(tmp_path):
    jpeg(tmp_path / '2020/01/15/a.jpg', b'a')
    jpeg(tmp_path / '2020/January/January_16/b.jpg', b'b')
    a = analyze_folder(tmp_path)
    tools = [r['tool'] for r in a['recommendations']]
    assert a['kind'] == 'drive' and tools[:2] == ['rename', 'fixdrive']
    assert a['dated_media'] == 2 and a['legacy_media'] == 1


def test_analyze_unorganized_and_empty(tmp_path):
    jpeg(tmp_path / 'Phone/IMG_20200101_101010.jpg', b'a')
    jpeg(tmp_path / 'Old PC/holiday/beach.jpg', b'b')
    (tmp_path / 'docs.pdf').write_bytes(b'x')
    (tmp_path / 'Old PC/empty.jpg').write_bytes(b'')
    a = analyze_folder(tmp_path)
    assert a['kind'] == 'unorganized' and a['recommendations'][0]['tool'] == 'filename'
    assert a['name_dated'] == 1 and a['empty'] == 1 and a['other'] == {'.pdf': 1}
    assert analyze_folder(tmp_path / 'Phone')['media'] == 1
    empty = tmp_path / 'nothing'
    empty.mkdir()
    assert analyze_folder(empty)['recommendations'] == []


# ── Regression tests for review findings ──────────────────────────────────────

@needs_exiftool
def test_twin_takes_over_when_first_copy_fails(tmp_path, monkeypatch):
    src = tmp_path / 'src'
    jpeg(src / 'a/IMG_20200101_120000.jpg', b'same')
    jpeg(src / 'b/IMG_20200101_120000 copy.jpg', b'same')
    real = shutil.copy2
    failed = {}

    def flaky(a, b, *k, **kw):
        if str(a).endswith('a/IMG_20200101_120000.jpg') and 'error' not in str(b) and not failed:
            failed['x'] = 1
            raise PermissionError(13, 'locked')
        return real(a, b, *k, **kw)
    monkeypatch.setattr(core.shutil, 'copy2', flaky)
    job = Processor(str(src), str(tmp_path / 'out'), workers=1,
                    options=ProcessOptions(use_sidecars=False, filename_tz='utc'))
    job.run()
    day = tmp_path / 'out/2020/January/January_01'
    assert [p.name for p in day.iterdir()] == ['IMG_20200101_120000 copy.jpg']
    assert (tmp_path / 'out/error/a/IMG_20200101_120000.jpg').exists()


@needs_exiftool
def test_old_flat_no_date_is_recognised(tmp_path):
    src = tmp_path / 'src'
    jpeg(src / 'Album/beach.jpg', b'u')
    out = tmp_path / 'out'
    jpeg(out / 'no-date/beach.jpg', b'u')                     # written by an older version
    m = core.Manifest(out)
    m.put(Path('no-date/beach.jpg'), core.quick_fingerprint(src / 'Album/beach.jpg'))
    m.save()
    job = Processor(str(src), str(out), options=ProcessOptions(use_sidecars=False))
    assert job.run() and job.stats.skipped == 1
    assert not (out / 'no-date/Album').exists()


def test_long_path_error_is_not_fatal():
    e = FileNotFoundError(2, 'path not found')
    e.winerror = 3
    assert not core.is_fatal_os_error(e)
    full = OSError(28, 'full')
    assert core.is_fatal_os_error(full)


def test_archive_with_corrupt_member_continues(tmp_path):
    d = tmp_path / 'dl'
    d.mkdir()
    payload = os.urandom(4000)
    with zipfile.ZipFile(d / 'a.zip', 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('Takeout/bad.jpg', payload)
        z.writestr('Takeout/good.jpg', JPEG)
    raw = bytearray((d / 'a.zip').read_bytes())
    i = raw.find(b'Takeout/bad.jpg') + len('Takeout/bad.jpg') + 40
    raw[i:i + 50] = bytes(50)                                   # damage the compressed data
    (d / 'a.zip').write_bytes(bytes(raw))
    with zipfile.ZipFile(d / 'b.zip', 'w') as z:
        z.writestr('Takeout/other.jpg', JPEG + b'b')
    job = ArchiveExtractor([str(d / 'a.zip'), str(d / 'b.zip')], str(tmp_path / 'out'))
    job.run()
    assert (tmp_path / 'out/Takeout/good.jpg').exists() and (tmp_path / 'out/Takeout/other.jpg').exists()
    assert job.stats.errors >= 1 and not list((tmp_path / 'out').rglob('.~photofix~*'))


def test_deep_archives_found_and_tar_progress(tmp_path):
    deep = tmp_path / 'a/b/c/d'
    deep.mkdir(parents=True)
    with tarfile.open(deep / 't.tgz', 'w:gz') as t:
        for i in range(5):
            ti = tarfile.TarInfo(f'Takeout/{i}.jpg')
            ti.size = len(JPEG)
            t.addfile(ti, io.BytesIO(JPEG))
    assert find_archives(tmp_path) == [deep / 't.tgz']
    seen = []
    job = ArchiveExtractor([str(deep / 't.tgz')], str(tmp_path / 'out'),
                           on_progress=lambda c, t, n, f: seen.append((c, t)))
    assert job.run()
    assert all(c <= t for c, t in seen) and job.stats.extracted == 5
    assert job._remaining_size(deep / 't.tgz') >= 0


@needs_exiftool
def test_wrong_extension_is_corrected(tmp_path):
    src = tmp_path / 'src'
    jpeg(src / 'Screenshot_2022-05-01-10-10-00.png', b'really a jpeg')
    job = Processor(str(src), str(tmp_path / 'out'), options=ProcessOptions(use_sidecars=False, filename_tz='utc'))
    assert job.run() and job.stats.meta_failed == 0 and job.stats.from_filename == 1
    out = tmp_path / 'out/2022/May/May_01/Screenshot_2022-05-01-10-10-00.jpg'
    assert read_tags(out, 'DateTimeOriginal')['DateTimeOriginal'] == '2022:05:01 10:10:00'
    assert 'corrected' in job.records[0].note
    assert core._real_extension('Not a valid PNG (looks more like a JPEG)') == '.jpg'
    assert core._real_extension('Error: something else') is None
