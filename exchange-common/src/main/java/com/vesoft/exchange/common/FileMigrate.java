/* Copyright (c) 2023 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class FileMigrate {
    //Logger log = Logger.getLogger(FileMigrate.class);


    /**
     * migrate the source file to target path
     *
     * @param sourceFile template config file
     * @param path       target path to save the config info
     */
    public void saveConfig(String sourceFile, String path) {
        InputStream inputStream =
                this.getClass().getClassLoader().getResourceAsStream(sourceFile);
        if (inputStream == null) {
            System.exit(-1);
        }
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        FileWriter writer = null;
        BufferedWriter bufferedWriter = null;
        BufferedReader reader = null;
        try {
            writer = new FileWriter(path);
            bufferedWriter = new BufferedWriter(writer);

            reader = new BufferedReader(new InputStreamReader(inputStream));
            String line = null;
            while ((line = reader.readLine()) != null) {
                bufferedWriter.write(line);
                bufferedWriter.write("\n");
            }
        } catch (IOException e) {
            System.out.println("Failed to migrate the template conf file:" + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (bufferedWriter != null) {
                    bufferedWriter.close();
                }
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                System.out.println("Failed to close the writer or reader:" + e.getMessage());
                e.printStackTrace();
            }
        }

    }
}
