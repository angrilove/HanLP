/*
 * <summary></summary>
 * <author>He Han</author>
 * <email>hankcs.cn@gmail.com</email>
 * <create-date>2014/5/10 12:42</create-date>
 *
 * <copyright file="WordDictionary.java" company="上海林原信息科技有限公司">
 * Copyright (c) 2003-2014, 上海林原信息科技有限公司. All Right Reserved, http://www.linrunsoft.com/
 * This source is subject to the LinrunSpace License. Please contact 上海林原信息科技有限公司 to get more information.
 * </copyright>
 */
package com.hankcs.hanlp.dictionary;


import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.collection.AhoCorasick.AhoCorasickDoubleArrayTrie;
import com.hankcs.hanlp.collection.trie.bintrie.BaseNode;
import com.hankcs.hanlp.collection.trie.bintrie.BinTrie;
import com.hankcs.hanlp.corpus.io.ByteArray;
import com.hankcs.hanlp.corpus.io.IOUtil;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.common.Vertex;
import com.hankcs.hanlp.utility.Predefine;
import com.hankcs.hanlp.utility.TextUtility;

import java.io.*;
import java.util.*;

import static com.hankcs.hanlp.utility.Predefine.logger;

/**
 * 用户自定义词典
 *
 * @author He Han
 */
public class CustomDictionary
{
    static BinTrie<CoreDictionary.Attribute> trie;
    static AhoCorasickDoubleArrayTrie<CoreDictionary.Attribute> act = new AhoCorasickDoubleArrayTrie<CoreDictionary.Attribute>();
    /**
     * 第一个是主词典，其他是副词典
     */
    public final static String path[] = HanLP.Config.CustomDictionaryPath;

    // 自动加载词典
    static
    {
        long start = System.currentTimeMillis();
        if (!loadMainDictionary(path[0]))
        {
            logger.warning("自定义词典" + Arrays.toString(path) + "加载失败");
        }
        else
        {
            logger.info("自定义词典加载成功:" + act.size() + "个词条，耗时" + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private static boolean loadMainDictionary(String mainPath)
    {
        logger.info("自定义词典开始加载:" + mainPath);
        if (loadDat(mainPath)) return true;
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<String, CoreDictionary.Attribute>();
        try
        {
            for (String p : path)
            {
                Nature defaultNature = Nature.n;
                int cut = p.indexOf(' ');
                if (cut > 0)
                {
                    // 有默认词性
                    String nature = p.substring(cut + 1);
                    p = p.substring(0, cut);
                    try
                    {
                        defaultNature = Nature.valueOf(nature);
                    }
                    catch (Exception e)
                    {
                        logger.severe("配置文件【" + p + "】写错了！" + e);
                        continue;
                    }
                }
                logger.info("加载自定义词典" + p + "中……");
                boolean success = load(p, defaultNature, map);
                if (!success) logger.warning("失败：" + p);
            }
            logger.info("正在构建AhoCorasickDoubleArrayTrie……");
            act.build(map);
            // 缓存成dat文件，下次加载会快很多
            logger.info("正在缓存词典为dat文件……");
            // 缓存值文件
            List<CoreDictionary.Attribute> attributeList = new LinkedList<CoreDictionary.Attribute>();
            for (Map.Entry<String, CoreDictionary.Attribute> entry : map.entrySet())
            {
                attributeList.add(entry.getValue());
            }
            DataOutputStream out = new DataOutputStream(new FileOutputStream(mainPath + Predefine.BIN_EXT));
            out.writeInt(attributeList.size());
            for (CoreDictionary.Attribute attribute : attributeList)
            {
                out.writeInt(attribute.totalFrequency);
                out.writeInt(attribute.nature.length);
                for (int i = 0; i < attribute.nature.length; ++i)
                {
                    out.writeInt(attribute.nature[i].ordinal());
                    out.writeInt(attribute.frequency[i]);
                }
            }
            act.save(out);
            out.close();
        }
        catch (FileNotFoundException e)
        {
            logger.severe("自定义词典" + mainPath + "不存在！" + e);
            return false;
        }
        catch (IOException e)
        {
            logger.severe("自定义词典" + mainPath + "读取错误！" + e);
            return false;
        }
        catch (Exception e)
        {
            logger.warning("自定义词典" + mainPath + "缓存失败！" + e);
        }
        return true;
    }


    /**
     * 加载用户词典（追加）
     * @param path 词典路径
     * @param defaultNature 默认词性
     * @return
     */
    public static boolean load(String path, Nature defaultNature, TreeMap<String, CoreDictionary.Attribute> map)
    {
        try
        {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null)
            {
                String[] param = line.split("\\s");
                if (CoreDictionary.contains(param[0]) || map.containsKey(param[0]))
                {
                    continue;
                }
                int natureCount = (param.length - 1) / 2;
                CoreDictionary.Attribute attribute;
                if (natureCount == 0)
                {
                    attribute = new CoreDictionary.Attribute(defaultNature);
                }
                else
                {
                    attribute = new CoreDictionary.Attribute(natureCount);
                    for (int i = 0; i < natureCount; ++i)
                    {
                        attribute.nature[i] = Enum.valueOf(Nature.class, param[1 + 2 * i]);
                        attribute.frequency[i] = Integer.parseInt(param[2 + 2 * i]);
                        attribute.totalFrequency += attribute.frequency[i];
                    }
                }
                map.put(param[0], attribute);
            }
            br.close();
        }
        catch (Exception e)
        {
            logger.severe("自定义词典" + path + "读取错误！" + e);
            return false;
        }

        return true;
    }

    /**
     * 往自定义词典中插入一个新词（非覆盖模式）
     * @param word 新词 如“裸婚”
     * @param natureWithFrequency 词性和其对应的频次，比如“nz 1 v 2”，null时表示“nz 1”
     * @return 是否插入成功（失败的原因可能是不覆盖、natureWithFrequency有问题等，后者可以通过调试模式了解原因）
     */
    public static boolean add(String word, String natureWithFrequency)
    {
        if (contains(word)) return false;
        return insert(word, natureWithFrequency);
    }

    /**
     * 增加新词
     * @param word
     * @return
     */
    public static boolean add(String word)
    {
        if (contains(word)) return false;
        return insert(word, null);
    }

    /**
     * 往自定义词典中插入一个新词（覆盖模式）
     * @param word 新词 如“裸婚”
     * @param natureWithFrequency 词性和其对应的频次，比如“nz 1 v 2”，null时表示“nz 1”
     * @return 是否插入成功（失败的原因可能是natureWithFrequency问题，可以通过调试模式了解原因）
     */
    public static boolean insert(String word, String natureWithFrequency)
    {
        if (word == null) return false;
        CoreDictionary.Attribute att = natureWithFrequency == null ? new CoreDictionary.Attribute(Nature.nz, 1) : CoreDictionary.Attribute.create(natureWithFrequency);
        if (att == null) return false;
        if (trie == null) trie = new BinTrie<CoreDictionary.Attribute>();
        trie.put(word, att);
        return true;
    }

    /**
     * 以覆盖模式增加新词
     * @param word
     * @return
     */
    public static boolean insert(String word)
    {
        return insert(word, null);
    }

    /**
     * 从磁盘加载双数组
     *
     * @param path
     * @return
     */
    static boolean loadDat(String path)
    {
        try
        {
            ByteArray byteArray = ByteArray.createByteArray(path + Predefine.BIN_EXT);
            int size = byteArray.nextInt();
            CoreDictionary.Attribute[] attributes = new CoreDictionary.Attribute[size];
            final Nature[] natureIndexArray = Nature.values();
            for (int i = 0; i < size; ++i)
            {
                // 第一个是全部频次，第二个是词性个数
                int currentTotalFrequency = byteArray.nextInt();
                int length = byteArray.nextInt();
                attributes[i] = new CoreDictionary.Attribute(length);
                attributes[i].totalFrequency = currentTotalFrequency;
                for (int j = 0; j < length; ++j)
                {
                    attributes[i].nature[j] = natureIndexArray[byteArray.nextInt()];
                    attributes[i].frequency[j] = byteArray.nextInt();
                }
            }
            if (!act.load(byteArray, attributes)) return false;
        }
        catch (Exception e)
        {
            logger.warning("读取失败，问题发生在" + e);
            return false;
        }
        return true;
    }

    /**
     * 查单词
     *
     * @param key
     * @return
     */
    public static CoreDictionary.Attribute get(String key)
    {
        return trie.get(key);
    }

    /**
     * 删除单词
     * @param key
     */
    public static void remove(String key)
    {
        trie.remove(key);
    }

    /**
     * 前缀查询
     *
     * @param key
     * @return
     */
    public static LinkedList<Map.Entry<String, CoreDictionary.Attribute>> commonPrefixSearch(String key)
    {
        return trie.commonPrefixSearchWithValue(key);
    }

    /**
     * 前缀查询
     *
     * @param chars
     * @param begin
     * @return
     */
    public static LinkedList<Map.Entry<String, CoreDictionary.Attribute>> commonPrefixSearch(char[] chars, int begin)
    {
        return trie.commonPrefixSearchWithValue(chars, begin);
    }

    public static BaseSearcher getSearcher(String text)
    {
        return new Searcher(text);
    }

    @Override
    public String toString()
    {
        return "CustomDictionary{" +
                "trie=" + trie +
                '}';
    }

    public static boolean contains(String key)
    {
        if (act.exactMatchSearch(key) >= 0) return true;
        return trie != null && trie.containsKey(key);
    }

    public static BaseSearcher getSearcher(char[] charArray)
    {
        return new Searcher(charArray);
    }

    static class Searcher extends BaseSearcher<CoreDictionary.Attribute>
    {
        /**
         * 分词从何处开始，这是一个状态
         */
        int begin;

        private LinkedList<Map.Entry<String, CoreDictionary.Attribute>> entryList;

        protected Searcher(char[] c)
        {
            super(c);
            entryList = new LinkedList<Map.Entry<String, CoreDictionary.Attribute>>();
        }

        protected Searcher(String text)
        {
            super(text);
            entryList = new LinkedList<Map.Entry<String, CoreDictionary.Attribute>>();
        }

        @Override
        public Map.Entry<String, CoreDictionary.Attribute> next()
        {
            // 保证首次调用找到一个词语
            while (entryList.size() == 0 && begin < c.length)
            {
                entryList = trie.commonPrefixSearchWithValue(c, begin);
                ++begin;
            }
            // 之后调用仅在缓存用完的时候调用一次
            if (entryList.size() == 0 && begin < c.length)
            {
                entryList = trie.commonPrefixSearchWithValue(c, begin);
                ++begin;
            }
            if (entryList.size() == 0)
            {
                return null;
            }
            Map.Entry<String, CoreDictionary.Attribute> result = entryList.getFirst();
            entryList.removeFirst();
            offset = begin - 1;
            return result;
        }
    }

    /**
     * 获取词典对应的trie树
     * @deprecated 谨慎操作，有可能废弃此接口
     * @return
     */
    public static BinTrie<CoreDictionary.Attribute> getTrie()
    {
        return trie;
    }

    public static void parseText(char[] text, AhoCorasickDoubleArrayTrie.IHit<CoreDictionary.Attribute> processor)
    {
        act.parseText(text, processor);
        if (trie != null)
        {
            BaseSearcher searcher = CustomDictionary.getSearcher(text);
            int offset;
            Map.Entry<String, CoreDictionary.Attribute> entry;
            while ((entry = searcher.next()) != null)
            {
                offset = searcher.getOffset();
                processor.hit(offset, offset + entry.getKey().length(), entry.getValue());
            }
        }
    }
}
