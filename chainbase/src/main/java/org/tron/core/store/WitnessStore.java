package org.tron.core.store;

import com.google.common.collect.Streams;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.Parameter;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.protos.Protocol;

@Slf4j(topic = "DB")
@Component
public class WitnessStore extends TronStoreWithRevoking<WitnessCapsule> {

  @Autowired
  private DynamicPropertiesStore dynamicPropertiesStore;
  private static final byte[] WITNESS_STANDBY_127 =
      "WITNESS_STANDBY_127".getBytes(StandardCharsets.UTF_8);

  @Autowired
  protected WitnessStore(@Value("witness") String dbName) {
    super(dbName);
  }

  /**
   * get all witnesses.
   */
  public List<WitnessCapsule> getAllWitnesses() {
    return Streams.stream(iterator())
        .map(Entry::getValue)
        .collect(Collectors.toList());
  }

  @Override
  public WitnessCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new WitnessCapsule(value);
  }

  public List<WitnessCapsule> getWitnessStandby() {
    return Optional.ofNullable(
        fromByteArray(dynamicPropertiesStore.getUnchecked(WITNESS_STANDBY_127)))
        .orElse(calWitnessStandby(null));
  }

  public List<WitnessCapsule> putWitnessStandby(List<WitnessCapsule> ret) {
    dynamicPropertiesStore.put(WITNESS_STANDBY_127, toByteArray(ret));
    return ret;
  }

  public List<WitnessCapsule> updateWitnessStandby(List<WitnessCapsule> all) {
    return putWitnessStandby(calWitnessStandby(all));
  }

  public List<WitnessCapsule> calWitnessStandby(List<WitnessCapsule> all) {
    List<WitnessCapsule> ret;
    if (all == null) {
      all = getAllWitnesses();
    }
    all.sort(Comparator.comparingLong(WitnessCapsule::getVoteCount)
        .reversed().thenComparing(Comparator.comparingInt(
            (WitnessCapsule w) -> w.getAddress().hashCode()).reversed()));
    if (all.size() > Parameter.ChainConstant.WITNESS_STANDBY_LENGTH) {
      ret = new ArrayList<>(all.subList(0, Parameter.ChainConstant.WITNESS_STANDBY_LENGTH));
    } else {
      ret = new ArrayList<>(all);
    }
    // trim voteCount = 0
    ret.removeIf(w -> w.getVoteCount() < 1);
    return ret;
  }

  private BytesCapsule toByteArray(List<WitnessCapsule> WitnessStandby ) {
    GrpcAPI.WitnessList witnessList = GrpcAPI.WitnessList.newBuilder().addAllWitnesses(
        WitnessStandby.stream().map(w -> Protocol.Witness.newBuilder()
            .setAddress(w.getAddress())
            .setVoteCount(w.getVoteCount()).build())
            .collect(Collectors.toList())).build();
    return new BytesCapsule(witnessList.toByteArray());
  }

  private List<WitnessCapsule> fromByteArray(BytesCapsule bytes) {
    if (bytes == null || bytes.getData() == null || bytes.getData().length == 0) {
      return null;
    }
    try {
      return GrpcAPI.WitnessList.parseFrom(bytes.getData())
          .getWitnessesList().stream().map(WitnessCapsule::new).collect(Collectors.toList());
    } catch (InvalidProtocolBufferException e) {
      logger.warn(e.getMessage(), e);
    }
    return null;
  }

}
