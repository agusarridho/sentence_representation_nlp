package uk.ac.ucl.cs.mr.statnlpbook.assignment3

import breeze.linalg.{DenseMatrix => Matrix, DenseVector => Vector}

import scala.collection.mutable

/**
 * @author rockt
 */
trait Model {
  /**
   * Stores all vector parameters
   */
  val vectorParams = new mutable.HashMap[String, VectorParam]()
  /**
   * Stores all matrix parameters
   */
  val matrixParams = new mutable.HashMap[String, MatrixParam]()
  /**
   * Maps a word to its trainable or fixed vector representation
   * @param word the input word represented as string
   * @return a block that evaluates to a vector/embedding for that word
   */
  def wordToVector(word: String): Block[Vector]
  /**
   * Composes a sequence of word vectors to a sentence vectors
   * @param words a sequence of blocks that evaluate to word vectors
   * @return a block evaluating to a sentence vector
   */
  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector]
  /**
   * Calculates the score of a sentence based on the vector representation of that sentence
   * @param sentence a block evaluating to a sentence vector
   * @return a block evaluating to the score between 0.0 and 1.0 of that sentence (1.0 positive sentiment, 0.0 negative sentiment)
   */
  def scoreSentence(sentence: Block[Vector]): Block[Double]
  /**
   * Predicts whether a sentence is of positive or negative sentiment (true: positive, false: negative)
   * @param sentence a tweet as a sequence of words
   * @param threshold the value above which we predict positive sentiment
   * @return whether the sentence is of positive sentiment
   */
  def predict(sentence: Seq[String])(implicit threshold: Double = 0.5): Boolean = {
    val wordVectors = sentence.map(wordToVector)
    val sentenceVector = wordVectorsToSentenceVector(wordVectors)
    scoreSentence(sentenceVector).forward() >= threshold
  }
  /**
   * Defines the training loss
   * @param sentence a tweet as a sequence of words
   * @param target the gold label of the tweet (true: positive sentiement, false: negative sentiment)
   * @return a block evaluating to the negative log-likelihod plus a regularization term
   */
  def loss(sentence: Seq[String], target: Boolean): Loss = {
    val targetScore = if (target) 1.0 else 0.0
    val wordVectors = sentence.map(wordToVector)
    val sentenceVector = wordVectorsToSentenceVector(wordVectors)
    val score = scoreSentence(sentenceVector)
    new LossSum(NegativeLogLikelihoodLoss(score, targetScore), regularizer(wordVectors))
  }
  /**
   * Regularizes the parameters of the model for a given input example
   * @param words a sequence of blocks evaluating to word vectors
   * @return a block representing the regularization loss on the parameters of the model
   */
  def regularizer(words: Seq[Block[Vector]]): Loss
}


/**
 * Problem 2
 * A sum of word vectors model
 * @param embeddingSize dimension of the word vectors used in this model
 * @param regularizationStrength strength of the regularization on the word vectors and global parameter vector w
 */
class SumOfWordVectorsModel(embeddingSize: Int, regularizationStrength: Double = 0.0) extends Model {
  /**
   * We use a lookup table to keep track of the word representations
   */
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  /**
   * We are also going to need another global vector parameter
   */
  vectorParams += "param_w" -> VectorParam(embeddingSize)

  def wordToVector(word: String): Block[Vector] = {
    if (vectorParams.contains(word))
      vectorParams(word)
    else
      LookupTable.addTrainableWordVector(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    Sum(words)
  }

  //h_theta(x) or Z
  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  def regularizer(words: Seq[Block[Vector]]): Loss = {
    val wordsAndGlobalW = vectorParams("param_w") +: words
    L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  }
}

/**
  * Problem 2
  * A sum of word vectors model
  * @param embeddingSize dimension of the word vectors used in this model
  * @param regularizationStrength strength of the regularization on the word vectors and global parameter vector w
  */
class SumMultOfWordVectorsModel(embeddingSize: Int, regularizationStrength: Double = 0.0) extends Model {
  /**
    * We use a lookup table to keep track of the word representations
    */
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  /**
    * We are also going to need another global vector parameter
    */
  vectorParams += "param_w" -> VectorParam(embeddingSize)

  def wordToVector(word: String): Block[Vector] = {
    if (vectorParams.contains(word))
      vectorParams(word)
    else
      LookupTable.addTrainableWordVector(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    if (words.size == 1)
      return Sum(words)
    else {
      var vectorMul = VectorMul(words(0), words(1))
      words.drop(2).foreach(aWord => {
        vectorMul = VectorMul(vectorMul, aWord)
      })

      return Sum(Seq(Sum(words), vectorMul))
    }

  }

  //h_theta(x) or Z
  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  def regularizer(words: Seq[Block[Vector]]): Loss = {
    val wordsAndGlobalW = vectorParams("param_w") +: words
    L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  }
}

/**
 * Problem 3
 * A recurrent neural network model
 * @param embeddingSize dimension of the word vectors used in this model
 * @param hiddenSize dimension of the hidden state vector used in this model
 * @param vectorRegularizationStrength strength of the regularization on the word vectors and global parameter vector w
 * @param matrixRegularizationStrength strength of the regularization of the transition matrices used in this model
 */
class RecurrentNeuralNetworkModel(embeddingSize: Int, hiddenSize: Int,
                                  vectorRegularizationStrength: Double = 0.0,
                                  matrixRegularizationStrength: Double = 0.0) extends Model {
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  vectorParams += "param_w" -> VectorParam(hiddenSize)
  vectorParams += "param_h0" -> VectorParam(hiddenSize)
  vectorParams += "param_b" -> VectorParam(hiddenSize)
  vectorParams("param_b").param := 1.0


  override val matrixParams: mutable.HashMap[String, MatrixParam] =
    new mutable.HashMap[String, MatrixParam]()
  matrixParams += "param_Wx" -> MatrixParam(hiddenSize, embeddingSize)
  matrixParams += "param_Wh" -> MatrixParam(hiddenSize, hiddenSize)

  def wordToVector(word: String): Block[Vector] = {
    if (vectorParams.contains(word))
      vectorParams(word)
    else
      LookupTable.addTrainableWordVector(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    vectorParams("param_h0").param := 0.0

    //just initialize with any value, as Scala does not allow me to declare a
    //variable without assigning it a value.
    var tanh = Tanh(VectorParam(1))

    //TODO: Think how to implement the foldLeft version of this method
    if (words.size >= 1) {
      val mul1 = Mul(matrixParams("param_Wh"), vectorParams("param_h0"))
      val mul2 = Mul(matrixParams("param_Wx"), words(0))
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }

    for(i <- 1 to words.size -1) {
      val aWord = words(i)
      val mul1 = Mul(matrixParams("param_Wh"), tanh)
      val mul2 = Mul(matrixParams("param_Wx"), aWord)
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }
    tanh
  }

  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  //val wordsAndGlobalW = vectorParams("param_w") +: words
  //L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  def regularizer(words: Seq[Block[Vector]]): Loss =
    new LossSum(
      L2Regularization(vectorRegularizationStrength, (vectorParams("param_w") +: words): _*),
      L2Regularization(matrixRegularizationStrength, matrixParams("param_Wx"), matrixParams("param_Wh"))
    )
}

class PreTrainedSumOfWordVectorsModel(embeddingSize: Int, regularizationStrength: Double = 0.0,
                                      preTrainedVectorsFile: String) extends Model {
  LookupTable.loadPreTrainedWordVectors(preTrainedVectorsFile, embeddingSize)

  /**
   * We use a lookup table to keep track of the word representations
   */
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  /**
   * We are also going to need another global vector parameter
   */
  vectorParams += "param_w" -> VectorParam(embeddingSize)

  def wordToVector(word: String): Block[Vector] = {
    LookupTable.getFixedOrTrained(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    Sum(words)
  }

  //h_theta(x) or Z
  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  def regularizer(words: Seq[Block[Vector]]): Loss = {
    val wordsAndGlobalW = vectorParams("param_w") +: words
    L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  }
}

class PreTrainedRecurrentNeuralNetworkModel(embeddingSize: Int, hiddenSize: Int,
                                  vectorRegularizationStrength: Double = 0.0,
                                  matrixRegularizationStrength: Double = 0.0,
                                  preTrainedVectorsFile: String) extends Model {

  LookupTable.loadPreTrainedWordVectors(preTrainedVectorsFile, embeddingSize)
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  vectorParams += "param_w" -> VectorParam(hiddenSize)
  vectorParams += "param_h0" -> VectorParam(hiddenSize)
  vectorParams += "param_b" -> VectorParam(hiddenSize)
  vectorParams("param_b").param := 1.0

  override val matrixParams: mutable.HashMap[String, MatrixParam] =
    new mutable.HashMap[String, MatrixParam]()
  matrixParams += "param_Wx" -> MatrixParam(hiddenSize, embeddingSize)
  matrixParams += "param_Wh" -> MatrixParam(hiddenSize, hiddenSize)

  def wordToVector(word: String): Block[Vector] = {
    LookupTable.getFixedOrTrained(word, hiddenSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    vectorParams("param_h0").param := 0.0

    //just initialize with any value, as Scala does not allow me to declare a
    //variable without assigning it a value.
    var tanh = Tanh(VectorParam(1))

    //TODO: Think how to implement the foldLeft version of this method
    if (words.size >= 1) {
      val mul1 = Mul(matrixParams("param_Wh"), vectorParams("param_h0"))
      val mul2 = Mul(matrixParams("param_Wx"), words(0))
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }

    for(i <- 1 to words.size -1) {
      val aWord = words(i)
      val mul1 = Mul(matrixParams("param_Wh"), tanh)
      val mul2 = Mul(matrixParams("param_Wx"), aWord)
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }
    tanh

  }

  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  //val wordsAndGlobalW = vectorParams("param_w") +: words
  //L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  def regularizer(words: Seq[Block[Vector]]): Loss =
    new LossSum(
      L2Regularization(vectorRegularizationStrength, (vectorParams("param_w") +: words): _*),
      L2Regularization(matrixRegularizationStrength, matrixParams("param_Wx"), matrixParams("param_Wh"))
    )
}

class RecurrentNeuralNetworkModelWithDropout(embeddingSize: Int, hiddenSize: Int,
                                  vectorRegularizationStrength: Double = 0.0,
                                  matrixRegularizationStrength: Double = 0.0,
                                  dropoutProb: Double = 0.5) extends Model {
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  vectorParams += "param_w" -> VectorParam(hiddenSize)
  vectorParams += "param_h0" -> VectorParam(hiddenSize)
  vectorParams += "param_b" -> VectorParam(hiddenSize)
  vectorParams("param_b").param := 1.0

  var isTestTime = false

  override val matrixParams: mutable.HashMap[String, MatrixParam] =
    new mutable.HashMap[String, MatrixParam]()
  matrixParams += "param_Wx" -> MatrixParam(hiddenSize, embeddingSize)
  matrixParams += "param_Wh" -> MatrixParam(hiddenSize, hiddenSize)

  def wordToVector(word: String): Block[Vector] = {
    if (vectorParams.contains(word))
      vectorParams(word)
    else
      LookupTable.addTrainableWordVector(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    vectorParams("param_h0").param := 0.0

    //just initialize with any value, as Scala does not allow me to declare a
    //variable without assigning it a value.
    var tanh = Tanh(VectorParam(1))
    //var tanh = Dropout(dropoutProb, vec(1), isTestTime)

    //TODO: Think how to implement the foldLeft version of this method
    if (words.size >= 1) {
      val mul1 = Mul(matrixParams("param_Wh"), vectorParams("param_h0"))
      val mul2 = Mul(matrixParams("param_Wx"), words(0))
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
      //tanh = Dropout(dropoutProb, Tanh(sum), isTestTime)
    }

    for(i <- 1 to words.size -1) {
      val aWord = words(i)
      val mul1 = Mul(matrixParams("param_Wh"), tanh)
      val mul2 = Mul(matrixParams("param_Wx"), aWord)
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
      //tanh = Dropout(dropoutProb, Tanh(sum), isTestTime)
    }
    Dropout(dropoutProb, tanh, isTestTime)
    //tanh
  }

  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  //val wordsAndGlobalW = vectorParams("param_w") +: words
  //L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  def regularizer(words: Seq[Block[Vector]]): Loss =
    new LossSum(
      L2Regularization(vectorRegularizationStrength, (vectorParams("param_w") +: words): _*),
      L2Regularization(matrixRegularizationStrength, matrixParams("param_Wx"), matrixParams("param_Wh"))
    )
}
/*
class RecurrentNeuralNetworkWithDropoutModel(embeddingSize: Int, hiddenSize: Int,
                                  vectorRegularizationStrength: Double = 0.0,
                                  matrixRegularizationStrength: Double = 0.0,
                                  dropoutProb: Double = 0.5) extends Model {
  override val vectorParams: mutable.HashMap[String, VectorParam] =
    LookupTable.trainableWordVectors
  vectorParams += "param_w" -> VectorParam(hiddenSize)
  vectorParams += "param_h0" -> VectorParam(hiddenSize)
  vectorParams += "param_b" -> VectorParam(hiddenSize)
  vectorParams("param_b").param := 1.0


  override val matrixParams: mutable.HashMap[String, MatrixParam] =
    new mutable.HashMap[String, MatrixParam]()
  matrixParams += "param_Wx" -> MatrixParam(hiddenSize, embeddingSize)
  matrixParams += "param_Wh" -> MatrixParam(hiddenSize, hiddenSize)

  def wordToVector(word: String): Block[Vector] = {
    if (vectorParams.contains(word))
      vectorParams(word)
    else
      LookupTable.addTrainableWordVector(word, embeddingSize)
  }

  def wordVectorsToSentenceVector(words: Seq[Block[Vector]]): Block[Vector] = {
    vectorParams("param_h0").param := 0.0

    //just initialize with any value, as Scala does not allow me to declare a
    //variable without assigning it a value.
    var tanh = Tanh(VectorParam(1))

    //TODO: Think how to implement the foldLeft version of this method
    if (words.size >= 1) {
      val mul1 = Mul(matrixParams("param_Wh"), vectorParams("param_h0"))
      val mul2 = Mul(matrixParams("param_Wx"), words(0))
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }

    for(i <- 1 to words.size -1) {
      val aWord = words(i)
      val mul1 = Mul(matrixParams("param_Wh"), tanh)
      val mul2 = Mul(matrixParams("param_Wx"), aWord)
      val sum = Sum(Seq(mul1, mul2, vectorParams("param_b")))
      tanh = Tanh(sum)
    }
    Dropout(dropoutProb, tanh)

  }

  def scoreSentence(sentence: Block[Vector]): Block[Double] = {
    Sigmoid(Dot(vectorParams("param_w"), sentence))
  }

  //val wordsAndGlobalW = vectorParams("param_w") +: words
  //L2Regularization(regularizationStrength, wordsAndGlobalW: _*)
  def regularizer(words: Seq[Block[Vector]]): Loss =
    new LossSum(
      L2Regularization(vectorRegularizationStrength, (vectorParams("param_w") +: words): _*),
      L2Regularization(matrixRegularizationStrength, matrixParams("param_Wx"), matrixParams("param_Wh"))
    )
}
*/
