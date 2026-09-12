"""Helpers for actual automatic Planning -> FD camera work in the synthetic search flow."""
import time


def seed(call, craft, key):
    planning = call(8104, 'GET', '/internal/simulation-planning-models/' + craft)
    model = {'spacecraftId': craft, 'missionDefinitionVersion': planning['body']['missionDefinitionVersion'],
             'environment': 'SIMULATION', 'pointingLaw': 'STARE_TARGET_TANGENT_PLANE_V1',
             'halfAngleAcrossDegrees': 1, 'halfAngleAlongDegrees': 1,
             'rasterColumns': 4096, 'rasterRows': 4096,
             'maximumGroundSampleDistanceMeters': 5,
             'maximumOffNadirDegrees': min(30, planning['body']['maximumOffNadirDegrees']),
             'minimumTargetElevationDegrees': 0, 'simulationPlanningModelVersion': planning['version'],
             'approvalReference': 'synthetic-automatic-camera-test',
             'provenance': 'Explicit simulation fixture; no SPACEEYE hardware specification'}
    return call(8104, 'POST', '/api/simulation-camera-models',
                {'expectedVersion': 0, 'model': model}, key + '-camera', 'admin')


def verify(call, run, camera):
    base = '/api/planning/runs/' + run['id']
    until = time.monotonic() + 90
    while time.monotonic() < until:
        status = call(8102, 'GET', base + '/camera-work')
        if status['status'] == 'EVALUATED':
            break
        assert status['status'] not in ('REJECTED', 'SUPERSEDED'), status
        time.sleep(1)
    else:
        raise AssertionError(status)
    assert status['camera_model_version'] == camera['version']
    result = call(8102, 'GET', base + '/camera/' + str(camera['version']))
    assessment = result['body']
    assert assessment['runId'] == run['id'] and assessment['cameraModel'] == camera
    assert assessment['scope'] == 'SAMPLED_CAMERA_EVIDENCE_EXPOSURE_AND_ATTITUDE_PENDING'
    assert len(assessment['candidates']) == len(run['run']['candidates'])
    area = run['geometry']['value']['area']
    for source, evaluated in zip(run['run']['candidates'], assessment['candidates']):
        assert evaluated['candidateId'] == source['id']['value']
        pointing = call(8103, 'GET', '/internal/required-target-pointing/' + evaluated['pointingResultId'])
        assert pointing['query']['horizon'] == source['activity']['window']
        assert pointing['query']['stepSeconds'] == 1
        manifest = evaluated['cameraResult']['body']
        assert manifest['query']['area'] == area
        assert manifest['planningModelHash'] == run['simulationModel']['sha256']
        assert manifest['sampleCount'] == len(pointing['samples'])
        artifact = call(8103, 'GET', '/internal/camera-footprint-evaluations/' + manifest['id'] + '/result')
        assert artifact['cameraModel'] == camera
        assert artifact['planningModel'] == run['simulationModel']['value']
        assert all(s['outcome'] == 'COMPUTED' and s['coverageFraction'] > .999999 for s in artifact['samples'])
    call(8102, 'GET', base + '/camera-work', user='requester', expected=403)
    call(8102, 'GET', base + '/camera/' + str(camera['version']), user='requester', expected=403)
    assert call(8102, 'GET', base) == run
    return {'assessmentId': result['id'], 'cameraModelVersion': camera['version'],
            'candidateCount': len(assessment['candidates']), 'workStatus': status['status'],
            'scope': assessment['scope']}
