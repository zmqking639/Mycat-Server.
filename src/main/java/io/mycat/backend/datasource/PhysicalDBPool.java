/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.backend.datasource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.heartbeat.DBHeartbeat;
import io.mycat.backend.mysql.nio.handler.GetConnectionHandler;
import io.mycat.backend.mysql.nio.handler.ResponseHandler;
import io.mycat.config.Alarms;
import io.mycat.config.model.DataHostConfig;

public class PhysicalDBPool {
	
	protected static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDBPool.class);
	
	public static final int BALANCE_NONE = 0;
	public static final int BALANCE_ALL_BACK = 1;
	public static final int BALANCE_ALL = 2;
    public static final int BALANCE_ALL_READ = 3;
    
	public static final int WRITE_ONLYONE_NODE = 0;
	public static final int WRITE_RANDOM_NODE = 1;
	public static final int WRITE_ALL_NODE = 2;
	
	public static final long LONG_TIME = 300000;
	public static final int WEIGHT = 0;

	private final String hostName;
	
	protected PhysicalDatasource[] writeSources;
	protected Map<Integer, PhysicalDatasource[]> readSources;
	
	protected volatile int activedIndex;
	protected volatile boolean initSuccess;
	
	protected final ReentrantLock switchLock = new ReentrantLock();
	private final Collection<PhysicalDatasource> allDs;
	private final int banlance;
	private final int writeType;
	private final Random random = new Random();
	private final Random wnrandom = new Random();
	private String[] schemas;
	private final DataHostConfig dataHostConfig;
	private String slaveIDs;

	public PhysicalDBPool(String name, DataHostConfig conf,
			PhysicalDatasource[] writeSources,
			Map<Integer, PhysicalDatasource[]> readSources, int balance,
			int writeType) {
		
		this.hostName = name;
		this.dataHostConfig = conf;
		this.writeSources = writeSources;
		this.banlance = balance;
		this.writeType = writeType;
		
		Iterator<Map.Entry<Integer, PhysicalDatasource[]>> entryItor = readSources.entrySet().iterator();
		while (entryItor.hasNext()) {
			PhysicalDatasource[] values = entryItor.next().getValue();
			if (values.length == 0) {
				entryItor.remove();
			}
		}
		
		this.readSources = readSources;
		this.allDs = this.genAllDataSources();
		
		LOGGER.info("total resouces of dataHost " + this.hostName + " is :" + allDs.size());
		
		setDataSourceProps();
	}

	public int getWriteType() {
		return writeType;
	}

	private void setDataSourceProps() {
		for (PhysicalDatasource ds : this.allDs) {
			ds.setDbPool(this);
		}
	}

	public PhysicalDatasource findDatasouce(BackendConnection exitsCon) {
		for (PhysicalDatasource ds : this.allDs) {
			if ((ds.isReadNode() == exitsCon.isFromSlaveDB())
					&& ds.isMyConnection(exitsCon)) {
					return ds;
			}
		}
		
		LOGGER.warn("can't find connection in pool " + this.hostName + " con:"	+ exitsCon);
		return null;
	}

	public String getSlaveIDs() {
		return slaveIDs;
	}

	public void setSlaveIDs(String slaveIDs) {
		this.slaveIDs = slaveIDs;
	}

	public String getHostName() {
		return hostName;
	}

	/**
	 * all write datanodes
	 * @return
	 */
	public PhysicalDatasource[] getSources() {
		return writeSources;
	}
	
	public PhysicalDatasource getSource() {
		
		switch (writeType) {
			case WRITE_ONLYONE_NODE: {
				return writeSources[activedIndex];
			}
			case WRITE_RANDOM_NODE: {
	
				int index = Math.abs(wnrandom.nextInt(Integer.MAX_VALUE)) % writeSources.length;
				PhysicalDatasource result = writeSources[index];
				if (!this.isAlive(result)) {
					
					// find all live nodes
					ArrayList<Integer> alives = new ArrayList<Integer>(writeSources.length - 1);
					for (int i = 0; i < writeSources.length; i++) {
						if (i != index
								&& this.isAlive(writeSources[i])) {
								alives.add(i);
						}
					}
					
					if (alives.isEmpty()) {
						result = writeSources[0];
					} else {						
						// random select one
						index = Math.abs(wnrandom.nextInt(Integer.MAX_VALUE)) % alives.size();
						result = writeSources[alives.get(index)];
	
					}
				}
				
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("select write source " + result.getName()
							+ " for dataHost:" + this.getHostName());
				}
				return result;
			}
			default: {
				throw new java.lang.IllegalArgumentException("writeType is "
						+ writeType + " ,so can't return one write datasource ");
			}
		}

	}

	public int getActivedIndex() {
		return activedIndex;
	}

	public boolean isInitSuccess() {
		return initSuccess;
	}

	public int next(int i) {
		if (checkIndex(i)) {
			return (++i == writeSources.length) ? 0 : i;
		} else {
			return 0;
		}
	}

	public boolean switchSource(int newIndex, boolean isAlarm, String reason) {
		if (this.writeType != PhysicalDBPool.WRITE_ONLYONE_NODE || !checkIndex(newIndex)) {
			return false;
		}
		
		final ReentrantLock lock = this.switchLock;
		lock.lock();
		try {
			int current = activedIndex;
			if (current != newIndex) {
				
				// switch index
				activedIndex = newIndex;
				
				// init again
				this.init(activedIndex);
				
				// clear all connections
				this.getSources()[current].clearCons("switch datasource");
				
				// write log
				LOGGER.warn(switchMessage(current, newIndex, false, reason));
				
				return true;
			}
		} finally {
			lock.unlock();
		}
		return false;
	}

	private String switchMessage(int current, int newIndex, boolean alarm, String reason) {
		StringBuilder s = new StringBuilder();
		if (alarm) {
			s.append(Alarms.DATANODE_SWITCH);
		}
		s.append("[Host=").append(hostName).append(",result=[").append(current).append("->");
		s.append(newIndex).append("],reason=").append(reason).append(']');
		return s.toString();
	}

	private int loop(int i) {
		return i < writeSources.length ? i : (i - writeSources.length);
	}

	public void init(int index) {
		
		if (!checkIndex(index)) {
			index = 0;
		}
		
		int active = -1;
		for (int i = 0; i < writeSources.length; i++) {
			int j = loop(i + index);
			if ( initSource(j, writeSources[j]) ) {

                //?????????-1????????????????????????   ?????????????????????
				boolean isNotSwitchDs = ( dataHostConfig.getSwitchType() == DataHostConfig.NOT_SWITCH_DS );
				if ( isNotSwitchDs && j > 0 ) {
					break;
				}

				active = j;
				activedIndex = active;
				initSuccess = true;
				LOGGER.info(getMessage(active, " init success"));

				if (this.writeType == WRITE_ONLYONE_NODE) {
					// only init one write datasource
					MycatServer.getInstance().saveDataHostIndex(hostName, activedIndex);
					break;
				}
			}
		}
		
		if (!checkIndex(active)) {
			initSuccess = false;
			StringBuilder s = new StringBuilder();
			s.append(Alarms.DEFAULT).append(hostName).append(" init failure");
			LOGGER.error(s.toString());
		}
	}

	private boolean checkIndex(int i) {
		return i >= 0 && i < writeSources.length;
	}

	private String getMessage(int index, String info) {
		return new StringBuilder().append(hostName).append(" index:").append(index).append(info).toString();
	}

	private boolean initSource(int index, PhysicalDatasource ds) {
		int initSize = ds.getConfig().getMinCon();
		
		LOGGER.info("init backend myqsl source ,create connections total " + initSize + " for " + ds.getName() + " index :" + index);
		
		CopyOnWriteArrayList<BackendConnection> list = new CopyOnWriteArrayList<BackendConnection>();
		GetConnectionHandler getConHandler = new GetConnectionHandler(list, initSize);
		// long start = System.currentTimeMillis();
		// long timeOut = start + 5000 * 1000L;

		for (int i = 0; i < initSize; i++) {
			try {
				ds.getConnection(this.schemas[i % schemas.length], true, getConHandler, null);
			} catch (Exception e) {
				LOGGER.warn(getMessage(index, " init connection error."), e);
			}
		}
		long timeOut = System.currentTimeMillis() + 60 * 1000;

		// waiting for finish
		while (!getConHandler.finished() && (System.currentTimeMillis() < timeOut)) {
			try {
				Thread.sleep(100);

			} catch (InterruptedException e) {
				LOGGER.error("initError", e);
			}
		}
		LOGGER.info("init result :" + getConHandler.getStatusInfo());
//		for (BackendConnection c : list) {
//			c.release();
//		}
		return !list.isEmpty();
	}

	public void doHeartbeat() {


		if (writeSources == null || writeSources.length == 0) {
			return;
		}

		for (PhysicalDatasource source : this.allDs) {

			if (source != null) {
				source.doHeartbeat();
			} else {
				StringBuilder s = new StringBuilder();
				s.append(Alarms.DEFAULT).append(hostName).append(" current dataSource is null!");
				LOGGER.error(s.toString());
			}
		}

	}

	/**
	 * back physical connection heartbeat check
	 */
	public void heartbeatCheck(long ildCheckPeriod) {
		
		for (PhysicalDatasource ds : allDs) {
			// only readnode or all write node or writetype=WRITE_ONLYONE_NODE
			// and current write node will check
			if (ds != null
					&& (ds.getHeartbeat().getStatus() == DBHeartbeat.OK_STATUS)
					&& (ds.isReadNode()
							|| (this.writeType != WRITE_ONLYONE_NODE) 
							|| (this.writeType == WRITE_ONLYONE_NODE 
							&& ds == this.getSource()))) {
				
				ds.heatBeatCheck(ds.getConfig().getIdleTimeout(), ildCheckPeriod);
			}
		}
	}

	public void startHeartbeat() {
		for (PhysicalDatasource source : this.allDs) {
			source.startHeartbeat();
		}
	}

	public void stopHeartbeat() {
		for (PhysicalDatasource source : this.allDs) {
			source.stopHeartbeat();
		}
	}

	/**
	 *  ???????????? dataSources
	 * @param reason
	 */
	public void clearDataSources(String reason) {
		LOGGER.info("clear datasours of pool " + this.hostName);
		for (PhysicalDatasource source : this.allDs) {			
			LOGGER.info("clear datasoure of pool  " + this.hostName + " ds:" + source.getConfig());
			source.clearCons(reason);
			source.stopHeartbeat();
		}
	}

	public Collection<PhysicalDatasource> genAllDataSources() {
		
		LinkedList<PhysicalDatasource> allSources = new LinkedList<PhysicalDatasource>();
		for (PhysicalDatasource ds : writeSources) {
			if (ds != null) {
				allSources.add(ds);
			}
		}
		
		for (PhysicalDatasource[] dataSources : this.readSources.values()) {
			for (PhysicalDatasource ds : dataSources) {
				if (ds != null) {
					allSources.add(ds);
				}
			}
		}
		return allSources;
	}

	public Collection<PhysicalDatasource> getAllDataSources() {
		return this.allDs;
	}

	/**
	 * return connection for read balance
	 *
	 * @param handler
	 * @param attachment
	 * @param database
	 * @throws Exception
	 */
	public void getRWBanlanceCon(String schema, boolean autocommit,
			ResponseHandler handler, Object attachment, String database) throws Exception {
		
		PhysicalDatasource theNode = null;
		ArrayList<PhysicalDatasource> okSources = null;
		switch (banlance) {
		case BALANCE_ALL_BACK: {			
			// all read nodes and the standard by masters
			okSources = getAllActiveRWSources(true, false, checkSlaveSynStatus());
			if (okSources.isEmpty()) {
				theNode = this.getSource();
				
			} else {
				theNode = randomSelect(okSources);
			}
			break;
		}
		case BALANCE_ALL: {
			okSources = getAllActiveRWSources(true, true, checkSlaveSynStatus());
			theNode = randomSelect(okSources);
			break;
		}
        case BALANCE_ALL_READ: {
            okSources = getAllActiveRWSources(false, false, checkSlaveSynStatus());
            theNode = randomSelect(okSources);
            break;
        }
		case BALANCE_NONE:
		default:
			// return default write data source
			theNode = this.getSource();
		}
		
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("select read source " + theNode.getName() + " for dataHost:" + this.getHostName());
		}
		//???????????????????????????
		theNode.setReadCount();
		theNode.getConnection(schema, autocommit, handler, attachment);
	}

	/**
	 * slave ??????????????????????????? readSource ????????????????????????
	 * @param schema
	 * @param autocommit
	 * @param handler
	 * @param attachment
	 * @param database
	 * @throws Exception
	 */
    public void getReadBanlanceCon(String schema, boolean autocommit, ResponseHandler handler, 
											Object attachment, String database)throws Exception {
		PhysicalDatasource theNode = null;
		ArrayList<PhysicalDatasource> okSources = null;
		okSources = getAllActiveRWSources(false, false, checkSlaveSynStatus());
		theNode = randomSelect(okSources);
		//???????????????????????????
		theNode.setReadCount();
		theNode.getConnection(schema, autocommit, handler, attachment);
	}
    
    /**
     * ??? writeHost ????????? readHost????????????????????? connection, ??????slave??????
     * @param schema
     * @param autocommit
     * @param handler
     * @param attachment
     * @param database
     * @return
     * @throws Exception
     */
    public boolean getReadCon(String schema, boolean autocommit, ResponseHandler handler, 
									Object attachment, String database)throws Exception {
		PhysicalDatasource theNode = null;
		
		LOGGER.debug("!readSources.isEmpty() " + !readSources.isEmpty());
		if (!readSources.isEmpty()) {
			int index = Math.abs(random.nextInt(Integer.MAX_VALUE)) % readSources.size();
			PhysicalDatasource[] allSlaves = this.readSources.get(index);
//			System.out.println("allSlaves.length " + allSlaves.length);
			if (allSlaves != null) {
				index = Math.abs(random.nextInt(Integer.MAX_VALUE)) % readSources.size();
				PhysicalDatasource slave = allSlaves[index];
				
				for (int i=0; i<allSlaves.length; i++) {
					LOGGER.debug("allSlaves.length i:::::: " + i);
					if (isAlive(slave)) {
						if (checkSlaveSynStatus()) {
							if (canSelectAsReadNode(slave)) {
								theNode = slave;
								break;
							} else {
								continue;
							}
						} else {
							theNode = slave;
							break;
						}
					}
//					index = Math.abs(random.nextInt()) % readSources.size();
				}
			}
			//???????????????????????????
			if(theNode != null) {
				theNode.setReadCount();
				theNode.getConnection(schema, autocommit, handler, attachment);
				return true;
			} else {
				LOGGER.warn("readhost is notavailable.");
				return false;
			}
		}else{
			LOGGER.warn("readhost is empty, readSources is empty.");
			return false;
		}
	} 
    
	private boolean checkSlaveSynStatus() {
		return ( dataHostConfig.getSlaveThreshold() != -1 )
				&& (dataHostConfig.getSwitchType() == DataHostConfig.SYN_STATUS_SWITCH_DS);
	}

	
	/**
	 * TODO: modify by zhuam
	 * 
	 * ?????????????????????????????????????????????
     * ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
	 * @param okSources
	 * @return
	 */
	public PhysicalDatasource randomSelect(ArrayList<PhysicalDatasource> okSources) {
		
		if (okSources.isEmpty()) {
			return this.getSource();
			
		} else {		
			
			int length = okSources.size(); 	// ?????????
	        int totalWeight = 0; 			// ?????????
	        boolean sameWeight = true; 		// ?????????????????????
	        for (int i = 0; i < length; i++) {	        	
	            int weight = okSources.get(i).getConfig().getWeight();
	            totalWeight += weight; 		// ???????????????	            
	            if (sameWeight && i > 0 
	            		&& weight != okSources.get(i-1).getConfig().getWeight() ) {	  // ??????????????????????????????          		            	
	                sameWeight = false; 	
	            }
	        }
	        
	        if (totalWeight > 0 && !sameWeight ) {
	            
	        	// ????????????????????????????????????0????????????????????????
	            int offset = random.nextInt(totalWeight);
	            
	            // ???????????????????????????????????????
	            for (int i = 0; i < length; i++) {
	                offset -= okSources.get(i).getConfig().getWeight();
	                if (offset < 0) {
	                    return okSources.get(i);
	                }
	            }
	        }
	        
	        // ??????????????????????????????0???????????????
	        return okSources.get( random.nextInt(length) );	
	        
			//int index = Math.abs(random.nextInt()) % okSources.size();
			//return okSources.get(index);
		}
	}
	
	//
    public int getBalance() {
        return banlance;
    }
    
	private boolean isAlive(PhysicalDatasource theSource) {
		return (theSource.getHeartbeat().getStatus() == DBHeartbeat.OK_STATUS);
	}

	private boolean canSelectAsReadNode(PhysicalDatasource theSource) {
		
		Integer slaveBehindMaster = theSource.getHeartbeat().getSlaveBehindMaster();
		int dbSynStatus = theSource.getHeartbeat().getDbSynStatus();
		
		if ( slaveBehindMaster == null || dbSynStatus == DBHeartbeat.DB_SYN_ERROR) {
			return false;
		}		
		boolean isSync = dbSynStatus == DBHeartbeat.DB_SYN_NORMAL;
		boolean isNotDelay = slaveBehindMaster < this.dataHostConfig.getSlaveThreshold();		
		return isSync && isNotDelay;
	}

	/**
     * return all backup write sources
     * 
     * @param includeWriteNode if include write nodes
     * @param includeCurWriteNode if include current active write node. invalid when <code>includeWriteNode<code> is false
     * @param filterWithSlaveThreshold
     *
     * @return
     */
	private ArrayList<PhysicalDatasource> getAllActiveRWSources(
    		boolean includeWriteNode, boolean includeCurWriteNode, boolean filterWithSlaveThreshold) {
		
		int curActive = activedIndex;
		ArrayList<PhysicalDatasource> okSources = new ArrayList<PhysicalDatasource>(this.allDs.size());
		
		for (int i = 0; i < this.writeSources.length; i++) {
			PhysicalDatasource theSource = writeSources[i];
			if (isAlive(theSource)) {// write node is active
                
				if (includeWriteNode) {					
					boolean isCurWriteNode = ( i == curActive );
					if ( isCurWriteNode && includeCurWriteNode == false) {
						// not include cur active source
					} else if (filterWithSlaveThreshold && theSource.isSalveOrRead() ) {	
						boolean selected = canSelectAsReadNode(theSource);
						if ( selected ) {
							okSources.add(theSource);
						} else {
							continue;
						}							
					} else {
						okSources.add(theSource);
					}
                }
                
				if (!readSources.isEmpty()) {					
					// check all slave nodes
					PhysicalDatasource[] allSlaves = this.readSources.get(i);
					if (allSlaves != null) {
						for (PhysicalDatasource slave : allSlaves) {
							if (isAlive(slave)) {								
								if (filterWithSlaveThreshold) {
									boolean selected = canSelectAsReadNode(slave);
									if ( selected ) {
										okSources.add(slave);
									} else {
										continue;
									}									
								} else {
									okSources.add(slave);
								}
							}
						}
					}
				}
				
			} else {
				
				// TODO : add by zhuam	
			    // ??????????????????OK, ????????????????????????????????????
				if ( this.dataHostConfig.isTempReadHostAvailable()
						&& !readSources.isEmpty()) {
				
						// check all slave nodes
						PhysicalDatasource[] allSlaves = this.readSources.get(i);
						if (allSlaves != null) {
							for (PhysicalDatasource slave : allSlaves) {
								if (isAlive(slave)) {
									
									if (filterWithSlaveThreshold) {									
										if (canSelectAsReadNode(slave)) {
											okSources.add(slave);
										} else {
											continue;
										}
										
									} else {
										okSources.add(slave);
									}
								}
							}
						}
				}
			}

		}
		return okSources;
	}

    public String[] getSchemas() {
		return schemas;
	}

	public void setSchemas(String[] mySchemas) {
		this.schemas = mySchemas;
	}
}
