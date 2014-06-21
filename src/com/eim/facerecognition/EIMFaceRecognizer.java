package com.eim.facerecognition;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.opencv.android.Utils;
import org.opencv.contrib.FaceRecognizer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.SparseArray;

import com.eim.facesmanagement.peopledb.Person;
import com.eim.facesmanagement.peopledb.Photo;

public class EIMFaceRecognizer {

	public enum Type {
		EIGEN, FISHER, LBPH;

		public boolean isIncrementable() {
			return this == LBPH;
		}
	}

	private static final String TAG = "EIMFaceRecognizer";
	private static String MODEL_FILE_NAME = "trainedModel.xml";
	private static boolean isTrained;
	private static String mModelPath;
	private static EIMFaceRecognizer instance;
	private static Type mRecognizerType;
	private static FaceRecognizer mFaceRecognizer;
	
	private SparseArray<Person> dataset;
	private Size size;

	private EIMFaceRecognizer(Context mContext, Type mType) {

		switch (mType) {
		case EIGEN:
			mFaceRecognizer = new EigenFaceRecognizer();
			break;
		case FISHER:
			mFaceRecognizer = new FisherFaceRecognizer();
			break;
		case LBPH:
			mFaceRecognizer = new LBPHFaceRecognizer();
			break;
		default:
			throw new IllegalArgumentException("Invalid mType");
		}

	}

	public static EIMFaceRecognizer getInstance(Context mContext, Type mType) {
		if (mContext == null)
			throw new IllegalArgumentException("mContext cannot be null");
		if (mType == null)
			throw new IllegalArgumentException("mType cannot be null");
		
		mModelPath = mContext.getExternalFilesDir(null).getAbsolutePath() + "/"
				+ MODEL_FILE_NAME;
		
		/*
		 * The problem was:
		 * --> Close application with type == EIGEN || Type == FISHER
		 * --> Open application
		 * 
		 * The default type in preferences is LBPH, hence the constructor found
		 * a trained Model performed by a different Face Recognizer
		 * 
		 * We have not to train the apps if the trainedModel already exists and
		 * it created using a Recognizer of the same type.
		 */
		
		if (mRecognizerType != mType) {
			mRecognizerType = mType;
			resetModel();
		}
		
		if (new File(mModelPath).exists()) {
			mFaceRecognizer.load(mModelPath);
			isTrained = true;
		} 

		if (instance != null)
			return instance;

		System.loadLibrary("facerecognizer");

		instance = new EIMFaceRecognizer(mContext.getApplicationContext(),mType);

		return instance;
	}

	/**
	 * Resets the trained model
	 */
	public static void resetModel() {
		File mModelFile = new File(mModelPath);
		if (mModelFile != null)
			mModelFile.delete();
		isTrained = false;
		
	}

	/**
	 * Train the recognizer with a new face.
	 * 
	 * @param newFace
	 *            the new face
	 * @param label
	 *            the id of the person related to the new face
	 */
	public void incrementalTrain(String newFacePath, int label) {
		if (!mRecognizerType.isIncrementable())
			throw new IllegalStateException("Face detector of type "
					+ mRecognizerType.toString()
					+ "cannot be trained incrementally");

		List<Mat> newFaces = new ArrayList<Mat>();
		Mat labels = new Mat(1, 1, CvType.CV_32SC1);
		Mat newFaceMat = new Mat();

		Bitmap newFace = BitmapFactory.decodeFile(newFacePath);
		if (newFace == null)
			return;

		Utils.bitmapToMat(newFace, newFaceMat);

		Imgproc.cvtColor(newFaceMat, newFaceMat, Imgproc.COLOR_RGB2GRAY);

		newFaces.add(newFaceMat);
		labels.put(0, 0, new int[] { label });

		if (isTrained) {
			mFaceRecognizer.update(newFaces, labels);
		}
		else {
			mFaceRecognizer.train(newFaces, labels);
		}

		mFaceRecognizer.save(mModelPath);
	}

	/**
	 * Train with the specified dataset. If the dataset contains no faces the
	 * model is reset
	 * 
	 * @param sparseArray
	 *            faces dataset, keys are the labels and faces are contained in
	 *            the field Photos of the value
	 */
	public void train(SparseArray<Person> sparseArray) {
		
		if (!isDatasetValid(sparseArray)) {
			resetModel();
			return;
		}

		Log.i(TAG, "Training!");
		List<Mat> faces = new ArrayList<Mat>();
		List<Integer> labels = new ArrayList<Integer>();
		int counter = 0;
		
		size = new Size(Double.MAX_VALUE,Double.MAX_VALUE);
		
		for (int i = 0, l = sparseArray.size(); i < l; i++) {
			
			int label = sparseArray.keyAt(i);
			Person person = sparseArray.valueAt(i);
			SparseArray<Photo> photos = person.getPhotos();
			
			for (int j = 0, k = photos.size(); j < k; j++) {
				Photo mPhoto = photos.valueAt(j);
				Mat mMat = new Mat();

				Bitmap face = mPhoto.getBitmap();
				if (face == null)
					face = BitmapFactory.decodeFile(mPhoto.getUrl());
				
				Utils.bitmapToMat(face, mMat);
				Imgproc.cvtColor(mMat, mMat, Imgproc.COLOR_RGB2GRAY);

				// For EIGEN and FISHER
				if (!mRecognizerType.isIncrementable()) {
					Size s = mMat.size();
					if (s.height < size.height)
						size.height = s.height;
					if (s.width < size.width)
						size.width = s.width;
				}
				
				labels.add((int) label);
				faces.add(mMat);
				
				Log.d(TAG, "Inserting " + label + ":" + mPhoto.getUrl());

				// labels.put(counter++, 0, new int[] { (int) label });
			}
		}
		
		// for EIGEN and FISHER
		
		if (!mRecognizerType.isIncrementable()) {
			Log.i(TAG, "Set size of all faces to " + size.width + "x" + size.height);
			ListIterator<Mat> itr = faces.listIterator();
			while (itr.hasNext()) {
				Mat src = itr.next();
				Mat dst = new Mat();
				Imgproc.resize(src, dst, size);
				itr.set(dst);
			}
		}

		
		Mat labelsMat = new Mat(labels.size(), 1, CvType.CV_32SC1);
		for (counter = 0; counter < labelsMat.rows(); counter++)
			labelsMat.put(counter, 0, new int[] { labels.get(counter) });
		
		Log.i(TAG, "Resizing done!");
		
		Log.i(TAG, labelsMat.dump());

		mFaceRecognizer.train(faces, labelsMat);
		mFaceRecognizer.save(mModelPath);

		isTrained = true;
	}

	private boolean isDatasetValid(SparseArray<Person> dataset) {
		if (dataset == null)
			return false;

		for (int i = 0, l = dataset.size(); i < l; i++)
			if (dataset.valueAt(i).getPhotos().size() > 0)
				return true;

		return false;
	}

	public void predict(Mat src, int[] label, double[] confidence) {
		Log.i(TAG, "Try prediction image. Type = " + mRecognizerType.name() );
		if (isTrained) {
			if (mRecognizerType.isIncrementable()) {
				mFaceRecognizer.predict(src, label, confidence);
			}
			else {
				Mat srcResized = new Mat();
				Imgproc.resize(src, srcResized, size);
				mFaceRecognizer.predict(srcResized, label, confidence);
			}
		}
		Log.i(TAG, "Confidence: " + confidence[0]);
	}

	public Type getType() {
		return mRecognizerType;
	}

	public void setType(Type mType) {
		if (mType == null)
			return;

		switch (mType) {

		case EIGEN:
			mFaceRecognizer = new EigenFaceRecognizer();
			break;
		case FISHER:
			mFaceRecognizer = new FisherFaceRecognizer();
			break;
		case LBPH:
			mFaceRecognizer = new LBPHFaceRecognizer();
			break;
		default:
			throw new IllegalArgumentException("Invalid mType");
		}
		
		mRecognizerType = mType;
		
		resetModel();
	}
	
	
}