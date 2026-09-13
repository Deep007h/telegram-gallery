#![allow(non_snake_case)]

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray};
use jni::sys::{jfloat, jfloatArray, jint, jstring};
use sha2::{Digest, Sha256};
use std::collections::HashSet;

// ── Pure Rust Core Algorithms ──

pub fn l2_normalize(vec: &mut [f32]) {
    let mut sum_sq: f32 = 0.0;
    for &val in vec.iter() {
        sum_sq += val * val;
    }
    let norm = sum_sq.sqrt();
    let norm = if norm < 1e-5 { 1e-5 } else { norm };
    for val in vec.iter_mut() {
        *val /= norm;
    }
}

pub fn cosine_similarity(v1: &[f32], v2: &[f32]) -> f32 {
    if v1.len() != v2.len() || v1.is_empty() {
        return 0.0;
    }
    let mut dot: f32 = 0.0;
    for i in 0..v1.len() {
        dot += v1[i] * v2[i];
    }
    dot.clamp(-1.0, 1.0)
}

pub fn batch_find_best_cluster(
    face: &[f32],
    centroids: &[f32],
    num_centroids: usize,
    dim: usize,
    threshold: f32,
    exclude_set: &HashSet<i32>,
) -> i32 {
    if face.len() != dim || centroids.len() != num_centroids * dim {
        return -1;
    }

    let mut best_idx = -1;
    let mut best_score = -1.0_f32;

    for i in 0..num_centroids {
        if exclude_set.contains(&(i as i32)) {
            continue;
        }

        let start = i * dim;
        let end = start + dim;
        let centroid = &centroids[start..end];

        let mut dot: f32 = 0.0;
        for j in 0..dim {
            dot += face[j] * centroid[j];
        }

        let score = dot.clamp(-1.0, 1.0);
        if score >= threshold && score > best_score {
            best_score = score;
            best_idx = i as i32;
        }
    }

    best_idx
}

pub fn update_centroid(old: &[f32], new_face: &[f32], count: i32) -> Option<Vec<f32>> {
    let len = old.len();
    if len != new_face.len() || len == 0 {
        return None;
    }

    let mut merged = vec![0.0f32; len];
    let count_f = count as f32;

    for i in 0..len {
        merged[i] = (old[i] * count_f + new_face[i]) / (count_f + 1.0);
    }
    l2_normalize(&mut merged);
    Some(merged)
}

pub fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    result.iter().map(|b| format!("{:02x}", b)).collect()
}

// ── JNI Helpers ──

macro_rules! handle_err {
    ($result:expr, $default:expr) => {
        match $result {
            Ok(v) => v,
            Err(_) => return $default,
        }
    };
}

fn read_float_array<'local>(env: &mut JNIEnv<'local>, array: &JFloatArray<'local>) -> Result<Vec<f32>, jni::errors::Error> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0.0f32; len];
    env.get_float_array_region(array, 0, &mut buf)?;
    Ok(buf)
}

fn read_int_array<'local>(env: &mut JNIEnv<'local>, array: &JIntArray<'local>) -> Result<Vec<i32>, jni::errors::Error> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0i32; len];
    env.get_int_array_region(array, 0, &mut buf)?;
    Ok(buf)
}

fn read_byte_array<'local>(env: &mut JNIEnv<'local>, array: &JByteArray<'local>) -> Result<Vec<u8>, jni::errors::Error> {
    let len = env.get_array_length(array)? as usize;
    let mut buf = vec![0i8; len];
    env.get_byte_array_region(array, 0, &mut buf)?;
    // transmute i8 to u8 safely
    let u8_buf = buf.into_iter().map(|b| b as u8).collect();
    Ok(u8_buf)
}

// ── JNI Native Function Exports ──

#[no_mangle]
pub extern "system" fn Java_com_teledrive_app_ai_RustFaceEngine_nativeL2Normalize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JFloatArray<'local>,
) -> jfloatArray {
    let mut vec = handle_err!(read_float_array(&mut env, &input), std::ptr::null_mut());
    l2_normalize(&mut vec);

    let result = handle_err!(env.new_float_array(vec.len() as i32), std::ptr::null_mut());
    handle_err!(env.set_float_array_region(&result, 0, &vec), std::ptr::null_mut());
    result.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_teledrive_app_ai_RustFaceEngine_nativeCosineSimilarity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    vec1: JFloatArray<'local>,
    vec2: JFloatArray<'local>,
) -> jfloat {
    let v1 = handle_err!(read_float_array(&mut env, &vec1), 0.0);
    let v2 = handle_err!(read_float_array(&mut env, &vec2), 0.0);
    cosine_similarity(&v1, &v2)
}

#[no_mangle]
pub extern "system" fn Java_com_teledrive_app_ai_RustFaceEngine_nativeBatchFindBestCluster<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    face_vec: JFloatArray<'local>,
    centroids_flat: JFloatArray<'local>,
    num_centroids: jint,
    dim: jint,
    threshold: jfloat,
    exclude_indices: JIntArray<'local>,
) -> jint {
    let face = handle_err!(read_float_array(&mut env, &face_vec), -1);
    let centroids = handle_err!(read_float_array(&mut env, &centroids_flat), -1);

    let exclude_vec = if !exclude_indices.is_null() {
        read_int_array(&mut env, &exclude_indices).unwrap_or_default()
    } else {
        vec![]
    };

    let mut exclude_set = HashSet::new();
    for idx in exclude_vec {
        exclude_set.insert(idx);
    }

    batch_find_best_cluster(
        &face,
        &centroids,
        num_centroids as usize,
        dim as usize,
        threshold,
        &exclude_set,
    )
}

#[no_mangle]
pub extern "system" fn Java_com_teledrive_app_ai_RustFaceEngine_nativeUpdateCentroid<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_centroid: JFloatArray<'local>,
    new_face: JFloatArray<'local>,
    count: jint,
) -> jfloatArray {
    let old = handle_err!(read_float_array(&mut env, &old_centroid), std::ptr::null_mut());
    let new_f = handle_err!(read_float_array(&mut env, &new_face), std::ptr::null_mut());

    let merged = handle_err!(
        update_centroid(&old, &new_f, count).ok_or(()),
        std::ptr::null_mut()
    );

    let result = handle_err!(env.new_float_array(merged.len() as i32), std::ptr::null_mut());
    handle_err!(env.set_float_array_region(&result, 0, &merged), std::ptr::null_mut());
    result.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_teledrive_app_ai_RustFaceEngine_nativeSha256Header<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jstring {
    let bytes = handle_err!(read_byte_array(&mut env, &data), std::ptr::null_mut());
    let hex = sha256_hex(&bytes);

    let j_str = handle_err!(env.new_string(hex), std::ptr::null_mut());
    j_str.into_raw()
}

// ── Unit Tests ──

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_l2_normalize() {
        let mut v = vec![3.0f32, 4.0f32];
        l2_normalize(&mut v);
        assert!((v[0] - 0.6).abs() < 1e-5);
        assert!((v[1] - 0.8).abs() < 1e-5);
    }

    #[test]
    fn test_cosine_similarity() {
        let v1 = vec![1.0f32, 0.0f32];
        let v2 = vec![1.0f32, 0.0f32];
        assert!((cosine_similarity(&v1, &v2) - 1.0).abs() < 1e-5);

        let v3 = vec![0.0f32, 1.0f32];
        assert!((cosine_similarity(&v1, &v3) - 0.0).abs() < 1e-5);
    }

    #[test]
    fn test_batch_find_best_cluster() {
        let dim = 4;
        let face = vec![1.0, 0.0, 0.0, 0.0];
        // 3 centroids:
        // 0: [0.0, 1.0, 0.0, 0.0] -> dot = 0
        // 1: [0.9, 0.1, 0.0, 0.0] -> dot = 0.9
        // 2: [0.7, 0.3, 0.0, 0.0] -> dot = 0.7
        let centroids = vec![
            0.0, 1.0, 0.0, 0.0,
            0.9, 0.1, 0.0, 0.0,
            0.7, 0.3, 0.0, 0.0,
        ];
        let mut exclude = HashSet::new();

        let best = batch_find_best_cluster(&face, &centroids, 3, dim, 0.5, &exclude);
        assert_eq!(best, 1);

        // Exclude cluster 1
        exclude.insert(1);
        let best_after_exclude = batch_find_best_cluster(&face, &centroids, 3, dim, 0.5, &exclude);
        assert_eq!(best_after_exclude, 2);
    }

    #[test]
    fn test_update_centroid() {
        let old = vec![1.0, 0.0];
        let new_f = vec![0.0, 1.0];
        let updated = update_centroid(&old, &new_f, 1).unwrap();
        // avg = [0.5, 0.5], normalized = [0.7071, 0.7071]
        assert!((updated[0] - updated[1]).abs() < 1e-5);
        let norm = (updated[0] * updated[0] + updated[1] * updated[1]).sqrt();
        assert!((norm - 1.0).abs() < 1e-5);
    }

    #[test]
    fn test_sha256_hex() {
        let hash = sha256_hex(b"hello world");
        assert_eq!(
            hash,
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        );
    }
}
