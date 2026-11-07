package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import msc.domain.time.MissionInstant;
import msc.platform.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real network download from gitlab.orekit.org. Invoke explicitly: -Dtest=ReferenceArchiveLiveIT. */
class ReferenceArchiveLiveIT {
  @TempDir Path root;

  @Test
  void downloadsAndValidatesCurrentOfficialOrekitData() throws Exception {
    var client = new OrekitDataClient(new Json(JsonMapper.builder().build()));
    String commit = client.latestCommit();
    var downloads = new ArrayList<OrekitDataClient.Download>();
    for (String path : ReferenceArchive.FILES) downloads.add(client.download(commit, path));
    // Host clock with the current real TAI-UTC (37 s since 2017-01-01, per tai-utc.dat).
    var now = MissionInstant.tai(java.time.Instant.now().getEpochSecond() + 37);
    var valid = new ReferenceArchive(root).validate(commit, downloads, now);
    System.out.printf(
        "LIVE orekit-data commit=%s archiveSha256=%s files=%s latestMeasuredTai=%d eopEndTai=%d"
            + " expiresTai=%d%n",
        commit, valid.sha256(), valid.files(), valid.latestMeasured().seconds(),
        valid.eopEnd().seconds(), valid.expiresAt().seconds());
    assertTrue(valid.expiresAt().compareTo(now) > 0);
  }
}
