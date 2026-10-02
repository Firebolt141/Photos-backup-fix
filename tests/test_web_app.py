"""Tests for the Flask layer: security guards and a full job via the API."""

import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

flask = pytest.importorskip('flask')
import web_app  # noqa: E402
from test_core import HAS_EXIFTOOL, jpeg, sidecar  # noqa: E402


@pytest.fixture
def client():
    web_app._allowed_hosts.clear()
    web_app._allowed_hosts.update({'localhost', '127.0.0.1:5000'})
    c = web_app.app.test_client()
    c.environ_base['HTTP_HOST'] = 'localhost'
    return c


H = {'X-Token': web_app.TOKEN}


def test_index_embeds_token(client):
    r = client.get('/')
    assert r.status_code == 200 and web_app.TOKEN in r.get_data(as_text=True)


def test_api_requires_token(client):
    assert client.get('/api/status').status_code == 403
    assert client.get('/api/status', headers={'X-Token': 'wrong'}).status_code == 403
    assert client.get('/api/status', headers=H).status_code == 200


def test_rejects_foreign_host(client):
    assert client.get('/', headers={'Host': 'attacker.example'}).status_code == 403


def test_post_requires_json(client):
    r = client.post('/api/run', data='tool=rename', headers={**H, 'Content-Type': 'text/plain'})
    assert r.status_code == 415


def test_validation_errors(client, tmp_path):
    r = client.post('/api/run', json={'tool': 'takeout', 'src': str(tmp_path / 'nope'), 'dst': 'x'}, headers=H)
    assert r.status_code == 400 and 'Source' in r.json['error']
    r = client.post('/api/run', json={'tool': 'takeout', 'src': str(tmp_path), 'dst': str(tmp_path)}, headers=H)
    assert r.status_code == 400
    r = client.post('/api/run', json={'tool': 'bogus'}, headers=H)
    assert r.status_code == 400


def _wait_done(client, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st = client.get('/api/status', headers=H).json
        if not st['running']:
            return st
        time.sleep(0.1)
    raise AssertionError('job did not finish')


@pytest.mark.skipif(not HAS_EXIFTOOL, reason='ExifTool not installed')
def test_takeout_job_via_api(client, tmp_path):
    src = tmp_path / 'src'
    jpeg(src / 'IMG_1.jpg', b'1')
    sidecar(src / 'IMG_1.jpg.json', 1562236200)
    jpeg(src / 'copy.jpg', b'1')                      # different-name duplicate
    r = client.post('/api/run', json={'tool': 'takeout', 'src': str(src), 'dst': str(tmp_path / 'out'),
                                      'check_dupes': True}, headers=H)
    assert r.status_code == 200
    t0 = time.time()
    while not client.get('/api/status', headers=H).json['awaiting_decision']:
        assert time.time() - t0 < 30
        time.sleep(0.05)
    assert client.post('/api/run', json={'tool': 'rename', 'root': str(tmp_path)}, headers=H).status_code == 409
    client.post('/api/decide', json={'action': 'skip_dupes'}, headers=H)
    st = _wait_done(client)
    assert st['last']['ok'] and st['stats']['from_sidecar'] == 1 and st['stats']['total'] == 1
    recs = client.get('/api/records?status=fixed', headers=H).json
    assert recs['total'] == 1 and recs['records'][0]['dest'].replace('\\', '/') == '2019/July/July_04/IMG_1.jpg'
    rep = client.get(f'/api/report?fmt=csv&t={web_app.TOKEN}')
    assert rep.status_code == 200 and b'file,status' in rep.data


@pytest.mark.skipif(not HAS_EXIFTOOL, reason='ExifTool not installed')
def test_cancel_during_duplicate_review(client, tmp_path):
    src = tmp_path / 'src'
    jpeg(src / 'a.jpg', b'1')
    jpeg(src / 'b.jpg', b'1')
    client.post('/api/run', json={'tool': 'takeout', 'src': str(src), 'dst': str(tmp_path / 'out'),
                                  'check_dupes': True}, headers=H)
    t0 = time.time()
    while not client.get('/api/status', headers=H).json['awaiting_decision']:
        assert time.time() - t0 < 30
        time.sleep(0.05)
    client.post('/api/decide', json={'action': 'cancel'}, headers=H)
    st = _wait_done(client)
    assert st['last']['stopped'] and not (tmp_path / 'out').exists()
