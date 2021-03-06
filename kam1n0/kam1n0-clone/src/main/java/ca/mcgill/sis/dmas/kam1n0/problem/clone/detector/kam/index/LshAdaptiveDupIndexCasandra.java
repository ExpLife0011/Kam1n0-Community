/*******************************************************************************
 * Copyright 2017 McGill University All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package ca.mcgill.sis.dmas.kam1n0.problem.clone.detector.kam.index;

import static com.datastax.driver.core.querybuilder.QueryBuilder.addAll;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.javaFunctions;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.typeConverter;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.querybuilder.QueryBuilder;
import ca.mcgill.sis.dmas.io.LineSequenceWriter;
import ca.mcgill.sis.dmas.io.Lines;
import ca.mcgill.sis.dmas.kam1n0.utils.datastore.CassandraInstance;
import ca.mcgill.sis.dmas.kam1n0.utils.executor.SparkInstance;
import scala.Tuple2;

public class LshAdaptiveDupIndexCasandra<T extends VecInfo, K extends VecInfoShared>
		extends LshAdaptiveDupFuncIndex<T, K> {

	private String databaseName;
	private CassandraInstance cassandraInstance;

	private static Logger logger = LoggerFactory.getLogger(LshAdaptiveDupIndexCasandra.class);

	public LshAdaptiveDupIndexCasandra(SparkInstance sparkInstance, CassandraInstance cassandraInstance,
			String databaseName) {
		super(sparkInstance);
		this.databaseName = databaseName;
		this.cassandraInstance = cassandraInstance;
	}

	// classes:
	public static final String _ADAPTIVE_HASH = "ADAPTIVE_HASH".toLowerCase();
	public static final String _ADAPTIVE_HASH_VIEW = _ADAPTIVE_HASH + "_view";

	// properties:
	public static final String _APP_ID = "rid0";
	public static final String _ADAPTIVE_HASH_HASHID = "hashid";
	public static final String _ADAPTIVE_HASH_IND = "ind";
	public static final String _ADAPTIVE_HASH_FULLKEY = "full_key";
	public static final String _ADAPTIVE_HASH_VIDS = "vids";
	public static final String _ADAPTIVE_HASH_SHARED_INFO = "share";

	private void createSchema() {
		if (!cassandraInstance.checkColumnFamilies(this.sparkInstance.getConf(), this.databaseName, _ADAPTIVE_HASH)) {
			this.cassandraInstance.doWithSession(this.sparkInstance.getConf(), session -> {
				session.execute("CREATE KEYSPACE if not exists " + databaseName + " WITH "
						+ "replication = {'class':'SimpleStrategy', 'replication_factor':1} "
						+ " AND durable_writes = true;");
				logger.info("Creating table {}.{}", this.databaseName, _ADAPTIVE_HASH);
				// session.execute("create type " + databaseName + ".ventry (fid
				// bigint, bid bigint, bl int, ps int);");
				session.execute("create table if not exists " + databaseName + "." + _ADAPTIVE_HASH + " (" //
						+ _APP_ID + " bigint," //
						+ _ADAPTIVE_HASH_HASHID + " bigint, " //
						+ _ADAPTIVE_HASH_IND + " int, " //
						+ _ADAPTIVE_HASH_FULLKEY + " blob, " //
						+ _ADAPTIVE_HASH_SHARED_INFO + " text, " //
						+ _ADAPTIVE_HASH_VIDS + " set<text>, " //
						+ " PRIMARY KEY ((" + _APP_ID + ")," + _ADAPTIVE_HASH_HASHID + ")" //
						+ ");");

				logger.info("Creating view {}.{}", this.databaseName, _ADAPTIVE_HASH_VIEW);
				session.execute("CREATE MATERIALIZED VIEW " + databaseName + "." + _ADAPTIVE_HASH_VIEW + " AS "//
						+ "SELECT * FROM " + databaseName + "." + _ADAPTIVE_HASH + " "//
						+ "WHERE " + _APP_ID + " IS NOT NULL AND " + _ADAPTIVE_HASH_HASHID + " IS NOT NULL AND "
						+ _ADAPTIVE_HASH_IND + " IS NOT NULL " + "PRIMARY KEY((" + _APP_ID + ", "
						+ _ADAPTIVE_HASH_HASHID + "), " + _ADAPTIVE_HASH_IND + ");");
			});
		} else {
			logger.info("Found table {}.{}", this.databaseName, _ADAPTIVE_HASH);
		}
	}

	@Override
	public void init() {
		this.createSchema();
	}

	@Override
	public void close() {

	}

	@Override
	public List<VecEntry<T, K>> update(long rid, List<VecEntry<T, K>> vecs) {
		return this.cassandraInstance.doWithSessionWithReturn(sparkInstance.getConf(), session -> {
			return vecs.stream().parallel()
					.map(vec -> new Tuple2<>(vec,
							session.execute(QueryBuilder.select(_ADAPTIVE_HASH_HASHID)
									.from(databaseName, _ADAPTIVE_HASH).where(eq(_APP_ID, rid)) //
									.and(eq(_ADAPTIVE_HASH_HASHID, vec.hashId))).one()))
					//
					.map(tp -> {
						VecEntry<T, K> vec = tp._1();

						HashSet<String> vals = vec.vids.stream().map(VecInfo::serialize)
								.collect(Collectors.toCollection(HashSet::new));

						if (tp._2 == null) {

							String shared = vec.sharedInfo.serialize();

							this.calFullKey(vec);
							if (vec.fullKey.length != 0) {
								session.executeAsync(QueryBuilder.insertInto(databaseName, _ADAPTIVE_HASH)//
										.value(_APP_ID, rid) //
										.value(_ADAPTIVE_HASH_HASHID, vec.hashId)//
										.value(_ADAPTIVE_HASH_IND, vec.ind)
										.value(_ADAPTIVE_HASH_FULLKEY, ByteBuffer.wrap(vec.fullKey))
										.value(_ADAPTIVE_HASH_SHARED_INFO, shared)//
										.value(_ADAPTIVE_HASH_VIDS, vals));
							}

							return vec;
						} else {
							session.executeAsync(QueryBuilder.update(databaseName, _ADAPTIVE_HASH)
									.with(addAll(_ADAPTIVE_HASH_VIDS, vals)) //
									.where(eq(_APP_ID, rid)) //
									.and(eq(_ADAPTIVE_HASH_HASHID, vec.hashId)));
							return null;
						}
					}).filter(vec -> vec != null).collect(Collectors.toList());

		});
	}

	public static class HidWrapper implements Serializable {
		private static final long serialVersionUID = 8243393648116173793L;

		public HidWrapper(Long hid) {
			this.hashid = hid;
		}

		public Long hashid;

		public Long getHashid() {
			return hashid;
		}

		public void setHashid(Long hashid) {
			this.hashid = hashid;
		}
	}

	// hid -> bid ( input is tbid->hid)
	// @Override
	// public JavaPairRDD<Long, Tuple2<T, D>> getVidsAsRDD(HashSet<Long> hids, int
	// topK) {
	// // remote
	// // original : functionId, hashId, blockId, blockLength, peerSize
	// // new : hashid, vecInfo
	//
	// JavaPairRDD<Long, Tuple2<T, D>> vals = javaFunctions(
	// sparkInstance.getContext().parallelize(new ArrayList<>(hids)).map(hid -> new
	// HidWrapper(hid)))
	// .joinWithCassandraTable(databaseName, _ADAPTIVE_HASH,
	// CassandraJavaUtil.someColumns(_ADAPTIVE_HASH_VIDS),
	// CassandraJavaUtil.someColumns(_ADAPTIVE_HASH_HASHID), //
	// GenericJavaRowReaderFactory.instance, //
	// CassandraJavaUtil.mapToRow(HidWrapper.class) //
	// ).filter(tp2 -> !tp2._2.isNullAt(0)).<Tuple2<Long, Tuple2<T, D>>>flatMap(tp2
	// -> {
	// Long hashId = tp2._1.hashid;
	// Set<String> vids = tp2._2.getSet(0, typeConverter(String.class));
	// return vids.stream().map(vid -> {
	// return new Tuple2<>(hashId, new Tuple2<>(VecInfo.<T>deSerialize(vid),
	// tp2._1.));
	// }).collect(Collectors.toList());
	// }).mapToPair(tp -> tp);
	//
	// return vals;

	// // (sid, (hid, fid))
	// JavaPairRDD<Long, Tuple2<Long, Long>> sid_hid_fid = vals
	// .mapToPair(entry -> new Tuple2<>(entry._2.blockId, new
	// Tuple2<>(entry._1, entry._2.functionId)));
	//
	// // (c_sid, (o_sid, o_fid o_hid))
	// JavaPairRDD<Long, Tuple3<Long, Long, Long>> csid_osid_ofid_ohid =
	// vals.flatMapToPair(entry -> {
	// return Arrays.stream(entry._2().calls)
	// .map(callee -> new Tuple2<>(callee, new Tuple3<>(entry._2.blockId,
	// entry._2.functionId, entry._1)))
	// .collect(Collectors.toList());
	// });
	//
	// // (c_sid, ((o_sid, o_fid o_hid), (c_hid, c_fid)))
	// JavaPairRDD<Long, Tuple2<Tuple3<Long, Long, Long>, Tuple2<Long,
	// Long>>> jointed = csid_osid_ofid_ohid
	// .join(sid_hid_fid).filter(tp -> tp._2._1._2() == tp._2._2._2);
	//
	// // (fid, o_hid, c_hid)
	// JavaPairRDD<Long, Tuple2<Long, Long>> slinks = jointed
	// .mapToPair(tp -> new Tuple2<>(tp._2._1._2(), new
	// Tuple2<>(tp._2._1._3(), tp._2._2._1)));
	//
	// Set<Long> tops = slinks.groupByKey().mapToPair(grouped -> {
	// long count = StreamSupport.stream(grouped._2.spliterator(),
	// false).distinct()
	// .filter(tp -> links.get(tp._1).contains(tp._2)).count();
	// return new Tuple2<>(grouped._1, count);
	// }).top(topK).stream().map(entry ->
	// entry._1).collect(Collectors.toSet());
	//
	// // finished ranking
	//
	// return vals.filter(val -> tops.contains(val._2.functionId))
	// .mapToPair(val -> new Tuple2<>(val._1, val._2.blockId));
	// }

	private static final String[] selectAllInfo = new String[] { _ADAPTIVE_HASH_HASHID, _ADAPTIVE_HASH_IND,
			_ADAPTIVE_HASH_FULLKEY, _ADAPTIVE_HASH_VIDS, _ADAPTIVE_HASH_SHARED_INFO };
	private static final String[] selectAllInfoButVidsAndSharedInfo = new String[] { _ADAPTIVE_HASH_HASHID,
			_ADAPTIVE_HASH_IND, _ADAPTIVE_HASH_FULLKEY };

	@Override
	public JavaRDD<VecEntry<T, K>> getVecEntryInfoAsRDD(long rid, HashSet<Long> hashIds, boolean excludeBlockIds) {

		String[] selection = null;
		if (excludeBlockIds)
			selection = selectAllInfoButVidsAndSharedInfo;
		else
			selection = selectAllInfo;

		return javaFunctions(this.sparkInstance.getContext())
				//
				.cassandraTable(databaseName, _ADAPTIVE_HASH_VIEW)
				//
				.select(selection)//
				.where(_APP_ID + " = ? AND " + _ADAPTIVE_HASH_HASHID + " in ?", rid, hashIds)//
				.filter(row -> !row.isNullAt(0) && !row.isNullAt(1) & !row.isNullAt(2))
				//
				.map(row -> {
					VecEntry<T, K> vec = new VecEntry<T, K>();
					vec.hashId = row.getLong(0);
					vec.ind = row.getInt(1);
					ByteBuffer bb = row.getBytes(2);
					vec.fullKey = new byte[bb.remaining()];
					bb.get(vec.fullKey);

					if (!excludeBlockIds) {
						Set<String> vids = row.getSet(3, typeConverter(String.class));
						vec.vids = vids.stream().map(VecInfo::<T>deSerialize)
								.collect(Collectors.toCollection(HashSet::new));
						String shared = row.getString(4);
						vec.sharedInfo = VecInfoShared.<K>deSerialize(shared);
					}

					return vec;
				});
	}

	public void dump(String file) {
		try {
			LineSequenceWriter writer = Lines.getLineWriter(file, false);
			javaFunctions(this.sparkInstance.getContext()).cassandraTable(databaseName, _ADAPTIVE_HASH)
					.select(_ADAPTIVE_HASH_HASHID, _ADAPTIVE_HASH_IND, _ADAPTIVE_HASH_FULLKEY, _ADAPTIVE_HASH_VIDS)
					.map(row -> {
						return row.toString();
					}).collect().forEach(writer::writeLineNoExcept);
			writer.close();
		} catch (Exception e) {
			logger.error("Failed to dump index.", e);
		}
	}

	@Override
	public void clear(long rid) {
		try {
			this.cassandraInstance.doWithSession(sess -> {
				sess.executeAsync(QueryBuilder.delete().from(databaseName, _ADAPTIVE_HASH)//
						.where(eq(_APP_ID, rid)));
			});
		} catch (Exception e) {
			logger.error("Failed to delete the index.", e);
		}
	}

}
