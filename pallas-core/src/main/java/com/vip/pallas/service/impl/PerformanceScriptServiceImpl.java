/**
 * Copyright 2019 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.pallas.service.impl;

import static java.nio.file.Files.newInputStream;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.alibaba.fastjson.JSON;
import com.google.common.net.HostAndPort;
import com.vip.pallas.bean.PerformanceData;
import com.vip.pallas.bean.QueryParamSetting;
import com.vip.pallas.exception.PallasException;
import com.vip.pallas.mybatis.entity.Index;
import com.vip.pallas.mybatis.entity.SearchTemplate;
import com.vip.pallas.service.IndexService;
import com.vip.pallas.service.PerformanceScriptService;
import com.vip.pallas.service.SearchTemplateService;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import freemarker.template.TemplateNotFoundException;

/**
 * ??????????????????????????????
 * 
 * @author timmy.hu
 *
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class PerformanceScriptServiceImpl implements PerformanceScriptService {

	private static final Logger logger = LoggerFactory.getLogger(PerformanceScriptServiceImpl.class);

	/**
	 * ???????????????
	 */
	private static final String ORDERBY = "orderby";

	/**
	 * ?????????????????????????????????????????????????????????????????????
	 */
	private static final String[] IGNORE_PARAMS = new String[] { ORDERBY };

	/**
	 * list???????????????
	 */
	private static final String LIST_KEY = "list";

	/**
	 * list???????????????
	 */
	private static final String OR_KEY = "or";

	/**
	 * ???????????????????????????ES???????????????
	 */
	private static final String[] ES_KEY_ARRAY = new String[] { LIST_KEY, OR_KEY };

	/**
	 * ?????????HTTP????????????
	 */
	private static final int DEFAULT_PORT = 80;

	/**
	 * ??????????????????
	 */
	private static final String PARAM_NAME = "paramName";

	/**
	 * jmx????????????????????????????????????????????????
	 */
	private static final String TPL_FILE_PATH = "/tpl";

	/**
	 * ?????????????????????????????????
	 */
	private static final String TPL_FILE_NAME = "performance_test_tpl.jmx";

	private static final String ES_SERVER_TEST_NAME = "es_server_list";

	private static final String ES_SERVER_FILE_NAME = "es_server_list.csv";

	private static final String ES_SERVER_KEYWORD = "es_server_host,es_server_port";

	@Autowired
	private SearchTemplateService searchTemplateService;
	
	@Autowired
	private IndexService indexService;

	@SuppressWarnings("unchecked")
	@Override
	public List<Map<String, String>> getQueryParamNames(SearchTemplate template) {
		List<Map<String, String>> result = new ArrayList<Map<String, String>>();
		Map<String, String> paramNameMap = null;
		Map<String, Object> params = searchTemplateService.genParams(template);
		for (Map.Entry<String, Object> en : params.entrySet()) {
			String paramKey = en.getKey();
			if (ArrayUtils.contains(IGNORE_PARAMS, paramKey)) {
				continue;
			}
			paramNameMap = new HashMap<>();
			Object paramValue = en.getValue();
			if (paramValue == null) {
				paramNameMap.put(PARAM_NAME, paramKey);
				result.add(paramNameMap);
			} else if (paramValue instanceof Map) {
				Map<String, Object> paramValueMap = (Map<String, Object>) paramValue;
				if (getMatchKey(paramValueMap) != null) {
					paramNameMap.put(PARAM_NAME, paramKey);
					result.add(paramNameMap);
				}
			}
		}
		return result;
	}

	private String getMatchKey(Map<String, Object> paramValueMap) {
		if (paramValueMap.size() != 1) {
			return null;
		}
		
		for (String key : ES_KEY_ARRAY) {
			if (paramValueMap.containsKey(key)) {
				return key;
			}
		}
		return null;
	}

	@Override
	public Map<String, Object> genSendQueryBody(SearchTemplate template, List<QueryParamSetting> paramSettings) {
		Map<String, Object> result = new HashMap<>();
		Map<String, Object> sendQueryParams = new LinkedHashMap<>();
		Map<String, Object> params = searchTemplateService.genParams(template);
		
		paramSettings.stream().filter(paramSetting -> params.containsKey(paramSetting.getParamName()))
				.forEach(paramSetting -> {
					String paramName = paramSetting.getParamName();
					Object orginParamValue = params.get(paramName);
					Object scriptParamValue = replaceWithScriptValue(orginParamValue, paramSetting.getScriptValue());
					if (scriptParamValue != null) {
						sendQueryParams.put(paramName, scriptParamValue);
					}
				});
		
		Index index = indexService.findById(template.getIndexId());
		String uniqueIdentifier = index.getIndexName() + "_" + template.getTemplateName();
		result.put("id", uniqueIdentifier);
		result.put("params", sendQueryParams);
		return result;
	}

	/**
	 * ??????????????????
	 * 
	 * ??????orginParamValue?????????Map?????????????????????????????????"list"???????????????"list"????????????????????????scriptValue?????????????????????????????????scriptValue
	 * 
	 * ???????????? {"list":[]} , ${channel_id}, ?????? {"list":"${channel_id}"}
	 * 
	 * 
	 * @param orginParamValue
	 * @param scriptValue
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Object replaceWithScriptValue(Object orginParamValue, String scriptValue) {
		if (orginParamValue instanceof Map) {
			Map<String, Object> paramValueMap = (Map<String, Object>) orginParamValue;
			String key = getMatchKey(paramValueMap);
			if (key != null) {
				paramValueMap.put(key, scriptValue);
				return paramValueMap;
			}
		}
		return scriptValue;
	}

	@Override
	public String genJmxScript(SearchTemplate template, List<PerformanceData> pds,
			List<List<QueryParamSetting>> paramSettingsList) throws PallasException {
		Index index = indexService.findById(template.getIndexId());
		String queryPath = "/" + index.getIndexName() + "/_search/template";
		List<Map<String, String>> testPlans = getTestPlans(template, paramSettingsList);
		Configuration cfg = initTemplateConfiguration();
		StringWriter sw = new StringWriter();
		setDataNames(pds);
		Map<String, Object> dataModel = getTplRootModel(queryPath, pds, testPlans);
		try {
			Template tplLoader = cfg.getTemplate(TPL_FILE_NAME);
			tplLoader.process(dataModel, sw);
			return sw.toString();
		} catch (TemplateNotFoundException tnfe) {
			throw new PallasException("????????????????????????????????????" + TPL_FILE_NAME, tnfe);
		} catch (Exception e) {
			throw new PallasException("?????????????????????????????????", e);
		} finally {
			IOUtils.closeQuietly(sw);
		}
	}

	/**
	 * ??????????????????Freemarker???????????????
	 * 
	 * @return
	 */
	private Configuration initTemplateConfiguration() {
		Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);
		cfg.setClassForTemplateLoading(this.getClass(), TPL_FILE_PATH);
		cfg.setDefaultEncoding("UTF-8");
		cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		return cfg;
	}

	/**
	 * ????????????????????????????????????
	 * 
	 * @param pds
	 */
	private void setDataNames(List<PerformanceData> pds) {
		if (CollectionUtils.isEmpty(pds)) {
			return;
		}
		int i = 1;
		pds.stream().filter(pd -> StringUtils.isEmpty(pd.getDataName())).forEach(pd -> pd.setDataName("dataFile" + i));
	}

	/**
	 * ??????Freemarker???????????????RootModel
	 * 
	 * @param host
	 * @param port
	 * @param queryPath
	 * @param pds
	 * @param testPlans
	 * @return
	 */
	private Map<String, Object> getTplRootModel(String queryPath, List<PerformanceData> pds,
			List<Map<String, String>> testPlans) {
		Map<String, Object> dataModel = new HashMap<String, Object>();
		dataModel.put("testPlans", testPlans);
		ArrayList<PerformanceData> pdsAddServer = addServerData(pds);
		dataModel.put("datas", pdsAddServer);
		dataModel.put("queryPath", queryPath);
		return dataModel;
	}

	private ArrayList<PerformanceData> addServerData(List<PerformanceData> pds) {
		ArrayList<PerformanceData> result = new ArrayList<PerformanceData>();
		PerformanceData serverData = new PerformanceData(ES_SERVER_TEST_NAME, ES_SERVER_KEYWORD, ES_SERVER_FILE_NAME,
				null);
		result.add(serverData);
		if (CollectionUtils.isNotEmpty(pds)) {
			result.addAll(pds);
		}
		return result;
	}

	/**
	 * ??????????????????????????????
	 * 
	 * @param template
	 * @param paramSettingsList
	 * @param index
	 * @return
	 */
	private List<Map<String, String>> getTestPlans(SearchTemplate template,
			List<List<QueryParamSetting>> paramSettingsList) throws PallasException {
		List<Map<String, String>> testPlans = new ArrayList<Map<String, String>>();
		if (CollectionUtils.isEmpty(paramSettingsList)) {
			logger.error("paramSettingsList is empty");
			throw new PallasException("?????????????????????????????????");
		}
		int testPlanCount = 1;
		Map<String, String> testPlan = null;
		for (List<QueryParamSetting> paramSettings : paramSettingsList) {
			Map<String, Object> queryBody = genSendQueryBody(template, paramSettings);
			String queryBodyStr = format4Jmx(JSON.toJSONString(queryBody));

			Index index = indexService.findById(template.getIndexId());
			String uniqueIdentifier = index.getIndexName() + "_" + template.getTemplateName();
			String testname = uniqueIdentifier + testPlanCount++;
			testPlan = new HashMap<String, String>();
			testPlan.put("testname", testname);
			testPlan.put("queryBody", queryBodyStr);
			testPlans.add(testPlan);
		}
		return testPlans;
	}

	/**
	 * ???json????????????????????????????????????JMX????????????????????????
	 * ?????????????????????????????????json???????????????"fields":{"list":"[\"text\"]"}??? ???????????????:
	 * "fields":{"list":["text"]} ?????????list?????????????????????json????????????????????????
	 * "channel_id":{"list":"${channel_id}"}??????????????????:"channel_id":{"list":${channel_id}}
	 * 
	 * ??????????????????JMX???xml??????????????????HTML????????????????????????????????????escape??????
	 * 
	 * @return
	 */
	private String format4Jmx(String script) {
		String newScript = replaceListQuote(replaceListBackSlant(script));
		return HtmlUtils.htmlEscape(newScript);
	}

	/**
	 * ?????????????????????????????????Json?????????????????????????????????????????????????????????jmx???script??????????????????????????????????????????????????????????????????
	 * 
	 * "fields":{"list":"[\"text\"]"} -> "fields":{"list":["text"]}
	 * 
	 * 
	 * @param script
	 * @return
	 */
	private String replaceListBackSlant(String script) {
		return script.replace("\"[\\", "[").replace("\\\"]\"", "\"]");
	}

	/**
	 * ???list??????????????? ${}??????????????????????????????,?????????????????????
	 * 
	 * 
	 * {"list":"${channel_id}"} -> {"list":${channel_id}}
	 * 
	 * 1. {"list":"${ ->{"list":${ <br>
	 * 2. }" -> }
	 * 
	 * @param script
	 * @return
	 */
	private String replaceListQuote(String script) {
		String toMatchStart = "\"list\":\"${";
		String toMatchStartReg = "(\"list\":\"\\$\\{)";
		String toMatchEnd = "}\"";
		String toReplaceStartReg = "\"list\":\\$\\{";
		String toReplaceEnd = "}";
		String result = script;
		while (true) {
			int startIndex = result.indexOf(toMatchStart);
			if (startIndex == -1) {
				break;
			}
			int leftIndex = startIndex + toMatchStart.length();
			String leftStr = result.substring(0, leftIndex).replaceFirst(toMatchStartReg, toReplaceStartReg);
			String rightStr = result.substring(leftIndex).replaceFirst(toMatchEnd, toReplaceEnd);
			result = leftStr + rightStr;
		}
		return result;
	}

	@Override
	public void zipFiles(String testname, String jmxScript, List<HostAndPort> hps, List<PerformanceData> pds,
			OutputStream os) throws IOException, PallasException {

		ZipOutputStream zos = new ZipOutputStream(os);
		try {
			zos.putNextEntry(new ZipEntry(testname + ".jmx"));
			zos.write(jmxScript.getBytes("UTF-8"));
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry(ES_SERVER_FILE_NAME));
			for (HostAndPort hp : hps) {
				String line = hp.getHostText() + ";" + String.valueOf(hp.getPortOrDefault(DEFAULT_PORT)) + "\r\n";
				zos.write(line.getBytes());
			}
			zos.closeEntry();
			if (CollectionUtils.isEmpty(pds)) {
				return;
			}
			for (PerformanceData pd : pds) {
				writeDataFile(zos, pd);
			}
		} finally {
			zos.flush();
			IOUtils.closeQuietly(zos);
		}
	}

	/**
	 * ???????????????????????????zip?????????????????????????????????????????????????????????????????????
	 * 
	 * @param zos
	 * @param pd
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	private void writeDataFile(ZipOutputStream zos, PerformanceData pd) throws IOException, PallasException {
		try(InputStream is = newInputStream(Paths.get(pd.getRealFileName()))) {
			zos.putNextEntry(new ZipEntry(pd.getFileName()));
			IOUtils.copy(is, zos);
		} catch (FileNotFoundException fne) {
			String errorMs = "??????????????????????????????????????????????????????" + pd.getFileName();
			logger.error(errorMs, fne);
			throw new PallasException(errorMs);
		} finally {
			zos.closeEntry();
		}
	}
}